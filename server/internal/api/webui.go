package api

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/model"
	"github.com/gabrielhidasy/PebblePinManager/server/internal/qrcode"
	"github.com/gabrielhidasy/PebblePinManager/server/internal/store"
)

const (
	uiSessionCookieName = "ppm_session"
	uiCSRFCookieName    = "ppm_csrf"
	uiCSRFHeaderName    = "X-CSRF-Token"

	uiSessionTTL      = 24 * time.Hour
	uiMaxSessions     = 1024
	maxUIPayloadBytes = 4 * 1024
)

// WebUIConfig controls the built-in browser dashboard and optional reverse-proxy SSO header.
type WebUIConfig struct {
	Enabled             bool
	AuthProxyHeader     string // Optional trusted proxy identity header (e.g. "Remote-User")
	AuthProxyNameHeader string // Optional trusted proxy display name header (e.g. "Remote-Name")
}

// WithWebUI enables the self-contained Web UI when cfg.Enabled is true.
func WithWebUI(cfg WebUIConfig) ServerOption {
	return func(s *Server) {
		if cfg.Enabled {
			s.webUI = WebUIConfig{
				Enabled:             true,
				AuthProxyHeader:     strings.TrimSpace(cfg.AuthProxyHeader),
				AuthProxyNameHeader: strings.TrimSpace(cfg.AuthProxyNameHeader),
			}
		}
	}
}

func (s *Server) registerWebUIRoutes() {
	s.mux.HandleFunc("GET /{$}", s.handleWebUIIndex)
	s.mux.HandleFunc("HEAD /{$}", s.handleWebUIIndex)

	s.mux.HandleFunc("GET /ui/style.css", s.handleWebUIStyle)
	s.mux.HandleFunc("HEAD /ui/style.css", s.handleWebUIStyle)
	s.mux.HandleFunc("GET /ui/app.js", s.handleWebUIScript)
	s.mux.HandleFunc("HEAD /ui/app.js", s.handleWebUIScript)

	s.mux.HandleFunc("GET /ui/api/session", s.handleUISession)
	s.mux.HandleFunc("HEAD /ui/api/session", s.handleUISession)
	s.mux.HandleFunc("POST /ui/api/login", s.requireCSRF(s.handleUILogin))
	s.mux.HandleFunc("POST /ui/api/logout", s.requireCSRF(s.handleUILogout))

	s.mux.HandleFunc("PUT /ui/api/profile", s.requireCSRF(s.requireUIAuth(s.handleUIUpdateProfile)))
	s.mux.HandleFunc("POST /ui/api/pairing", s.requireCSRF(s.requireUIAuth(s.handleUIPairing)))
	s.mux.HandleFunc("POST /ui/api/tokens/reveal", s.requireCSRF(s.requireUIAuth(s.handleUIRevealTokens)))
	s.mux.HandleFunc("POST /ui/api/tokens/rotate", s.requireCSRF(s.requireUIAuth(s.handleUIRotateTokens)))

	s.mux.HandleFunc("GET /ui/api/pins", s.requireUIAuth(s.handleUIListPins))
	s.mux.HandleFunc("HEAD /ui/api/pins", s.requireUIAuth(s.handleUIListPins))
	s.mux.HandleFunc("POST /ui/api/pins", s.requireCSRF(s.requireUIAuth(s.handleUICreatePin)))
	s.mux.HandleFunc("PUT /ui/api/pins/{id}", s.requireCSRF(s.requireUIAuth(s.handleUIUpsertPin)))
	s.mux.HandleFunc("DELETE /ui/api/pins/{id}", s.requireCSRF(s.requireUIAuth(s.handleUIDeletePin)))
}

type uiSession struct {
	ID        string
	AuthMode  string // "user_token" or "admin_token"
	UserID    string
	CreatedAt time.Time
	ExpiresAt time.Time
}

type uiSessionStore struct {
	mu       sync.Mutex
	now      func() time.Time
	sessions map[string]uiSession
}

func newUISessionStore(now func() time.Time) *uiSessionStore {
	if now == nil {
		now = time.Now
	}
	return &uiSessionStore{
		now:      now,
		sessions: make(map[string]uiSession),
	}
}

func (st *uiSessionStore) Create(authMode, userID string) (string, error) {
	id, err := randomHex32()
	if err != nil {
		return "", err
	}

	st.mu.Lock()
	defer st.mu.Unlock()

	now := st.now()
	for k, v := range st.sessions {
		if !v.ExpiresAt.After(now) {
			delete(st.sessions, k)
		}
	}
	if len(st.sessions) >= uiMaxSessions {
		var oldestKey string
		var oldestTime time.Time
		for k, v := range st.sessions {
			if oldestKey == "" || v.CreatedAt.Before(oldestTime) {
				oldestKey = k
				oldestTime = v.CreatedAt
			}
		}
		if oldestKey != "" {
			delete(st.sessions, oldestKey)
		}
	}

	st.sessions[id] = uiSession{
		ID:        id,
		AuthMode:  authMode,
		UserID:    userID,
		CreatedAt: now,
		ExpiresAt: now.Add(uiSessionTTL),
	}
	return id, nil
}

func (st *uiSessionStore) Get(id string) (uiSession, bool) {
	if id == "" {
		return uiSession{}, false
	}
	st.mu.Lock()
	defer st.mu.Unlock()

	sess, ok := st.sessions[id]
	if !ok {
		return uiSession{}, false
	}
	if !sess.ExpiresAt.After(st.now()) {
		delete(st.sessions, id)
		return uiSession{}, false
	}
	return sess, true
}

func (st *uiSessionStore) Delete(id string) {
	if id == "" {
		return
	}
	st.mu.Lock()
	delete(st.sessions, id)
	st.mu.Unlock()
}

func (st *uiSessionStore) InvalidateUser(userID string) {
	if userID == "" {
		return
	}
	st.mu.Lock()
	for k, v := range st.sessions {
		if v.UserID == userID {
			delete(st.sessions, k)
		}
	}
	st.mu.Unlock()
}

func (st *uiSessionStore) InvalidateUserExcept(userID, keepSessionID string) {
	if userID == "" {
		return
	}
	st.mu.Lock()
	for k, v := range st.sessions {
		if v.UserID == userID && k != keepSessionID {
			delete(st.sessions, k)
		}
	}
	st.mu.Unlock()
}

type uiIdentity struct {
	AuthMode     string // "proxy", "user_token", or "admin_token"
	ProxySubject string
	SessionID    string
	IsLegacy     bool
	User         store.UserSummary
	Store        *store.Store
}

func (s *Server) extractProxyIdentity(r *http.Request) (subject string, displayName string, ok bool) {
	if !s.trustProxy || s.webUI.AuthProxyHeader == "" {
		return "", "", false
	}
	raw := strings.TrimSpace(r.Header.Get(s.webUI.AuthProxyHeader))
	if raw == "" {
		return "", "", false
	}
	if _, valErr := model.ValidateExternalSubject(raw); valErr != nil {
		return "", "", false
	}
	if s.webUI.AuthProxyNameHeader != "" {
		displayName = strings.TrimSpace(r.Header.Get(s.webUI.AuthProxyNameHeader))
	}
	return raw, displayName, true
}

func (s *Server) resolveUIIdentity(r *http.Request) (*uiIdentity, int, string, string) {
	// 1. Trusted reverse-proxy identity (e.g. Authelia Remote-User).
	if subject, displayName, ok := s.extractProxyIdentity(r); ok {
		if s.users == nil {
			return nil, http.StatusServiceUnavailable, "multi_user_unavailable", "Multi-user store is not configured."
		}
		user, _, err := s.users.GetOrCreateUserBySubject(subject, displayName)
		if err != nil {
			if errors.Is(err, store.ErrUserLimitReached) {
				return nil, http.StatusForbidden, "user_limit_reached", "Maximum number of registered users has been reached."
			}
			s.logger.Error("proxy_user_provision_failed", "err", err)
			return nil, http.StatusInternalServerError, "internal_error", "Failed to provision authenticated user."
		}
		st, err := s.users.PinStore(user.ID)
		if err != nil {
			s.logger.Error("proxy_user_store_failed", "userId", user.ID, "err", err)
			return nil, http.StatusInternalServerError, "internal_error", "Failed to load user pin store."
		}
		return &uiIdentity{
			AuthMode:     "proxy",
			ProxySubject: user.ExternalSubject,
			User:         user,
			Store:        st,
		}, 0, "", ""
	}

	// 2. Standalone browser session cookie (per-user write token or global admin write token).
	if c, err := r.Cookie(uiSessionCookieName); err == nil && c.Value != "" {
		sess, ok := s.sessions.Get(c.Value)
		if ok {
			switch sess.AuthMode {
			case "user_token":
				if s.users == nil {
					s.sessions.Delete(sess.ID)
					return nil, 0, "", ""
				}
				user, exists := s.users.GetUser(sess.UserID)
				if !exists {
					s.sessions.Delete(sess.ID)
					return nil, 0, "", ""
				}
				st, err := s.users.PinStore(user.ID)
				if err != nil {
					s.logger.Error("session_user_store_failed", "userId", user.ID, "err", err)
					return nil, http.StatusInternalServerError, "internal_error", "Failed to load user pin store."
				}
				return &uiIdentity{
					AuthMode:  "user_token",
					SessionID: sess.ID,
					User:      user,
					Store:     st,
				}, 0, "", ""
			case "admin_token":
				return &uiIdentity{
					AuthMode:  "admin_token",
					SessionID: sess.ID,
					IsLegacy:  true,
					Store:     s.store,
				}, 0, "", ""
			}
		}
	}

	return nil, 0, "", ""
}

type uiAuthHandlerFunc func(w http.ResponseWriter, r *http.Request, id *uiIdentity)

func (s *Server) requireUIAuth(next uiAuthHandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, errStatus, errCode, errMsg := s.resolveUIIdentity(r)
		if errStatus != 0 {
			writeError(w, r, errStatus, errCode, errMsg)
			return
		}
		if id == nil {
			writeError(w, r, http.StatusUnauthorized, "unauthorized", "Sign in required.")
			return
		}
		if rec, ok := w.(*statusRecorder); ok {
			rec.role = RoleWrite
		}
		next(w, r, id)
	}
}

func (s *Server) requireCSRF(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(uiCSRFCookieName)
		if err != nil || !isHex64(cookie.Value) {
			writeError(w, r, http.StatusForbidden, "csrf_failed", "Missing or invalid CSRF token. Reload the page and try again.")
			return
		}
		header := strings.TrimSpace(r.Header.Get(uiCSRFHeaderName))
		if !isHex64(header) || subtle.ConstantTimeCompare([]byte(cookie.Value), []byte(header)) != 1 {
			writeError(w, r, http.StatusForbidden, "csrf_failed", "Missing or invalid CSRF token. Reload the page and try again.")
			return
		}
		next(w, r)
	}
}

func (s *Server) ensureCSRFCookie(w http.ResponseWriter, r *http.Request) (string, error) {
	if c, err := r.Cookie(uiCSRFCookieName); err == nil && isHex64(c.Value) {
		return c.Value, nil
	}
	token, err := randomHex32()
	if err != nil {
		return "", err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     uiCSRFCookieName,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   s.isHTTPSRequest(r),
		SameSite: http.SameSiteStrictMode,
		MaxAge:   int((7 * 24 * time.Hour).Seconds()),
	})
	return token, nil
}

func (s *Server) isHTTPSRequest(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	if strings.HasPrefix(strings.ToLower(s.effectivePublicURL()), "https://") {
		return true
	}
	if s.trustProxy && strings.EqualFold(firstCSVHeader(r.Header.Get("X-Forwarded-Proto")), "https") {
		return true
	}
	return false
}

func (s *Server) setSessionCookie(w http.ResponseWriter, r *http.Request, sessionID string) {
	http.SetCookie(w, &http.Cookie{
		Name:     uiSessionCookieName,
		Value:    sessionID,
		Path:     "/",
		HttpOnly: true,
		Secure:   s.isHTTPSRequest(r),
		SameSite: http.SameSiteStrictMode,
		MaxAge:   int(uiSessionTTL.Seconds()),
	})
}

func (s *Server) clearSessionCookie(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{
		Name:     uiSessionCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		Secure:   s.isHTTPSRequest(r),
		SameSite: http.SameSiteStrictMode,
		MaxAge:   -1,
	})
}

func (s *Server) setUINoStoreHeaders(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "private, no-store, max-age=0")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")
	vary := "Cookie"
	if s.webUI.AuthProxyHeader != "" {
		vary += ", " + s.webUI.AuthProxyHeader
	}
	w.Header().Set("Vary", vary)
}

func (s *Server) handleWebUIIndex(w http.ResponseWriter, r *http.Request) {
	csrfToken, err := s.ensureCSRFCookie(w, r)
	if err != nil {
		s.logger.Error("csrf_token_generate_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to initialize session.")
		return
	}

	var buf bytes.Buffer
	if err := webUIIndexTemplate.Execute(&buf, webUIIndexData{
		CSRFToken:  csrfToken,
		ServerName: s.effectiveServerName(),
	}); err != nil {
		s.logger.Error("webui_index_template_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to render Web UI.")
		return
	}

	s.setUINoStoreHeaders(w)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; form-action 'self'; frame-ancestors 'none'")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(buf.Bytes())
	}
}

func (s *Server) handleWebUIStyle(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/css; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write([]byte(webUICSS))
	}
}

func (s *Server) handleWebUIScript(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write([]byte(webUIJS))
	}
}

type uiSessionResponse struct {
	Authenticated       bool                  `json:"authenticated"`
	AuthMode            string                `json:"authMode,omitempty"`
	ProxySubject        string                `json:"proxySubject,omitempty"`
	IsLegacy            bool                  `json:"isLegacy,omitempty"`
	ServerName          string                `json:"serverName,omitempty"`
	RegistrationEnabled bool                  `json:"registrationEnabled"`
	AuthProxyConfigured bool                  `json:"authProxyConfigured"`
	User                *userMetadataResponse `json:"user,omitempty"`
	FeedURL             string                `json:"feedUrl,omitempty"`
	PinsAPIURL          string                `json:"pinsApiUrl,omitempty"`
	TokensAvailable     bool                  `json:"tokensAvailable,omitempty"`
	IsHTTP              bool                  `json:"isHttp,omitempty"`
	IsLocalhost         bool                  `json:"isLocalhost,omitempty"`
}

func (s *Server) buildSessionResponse(r *http.Request, id *uiIdentity) (uiSessionResponse, error) {
	baseURL, hostName := s.resolveBaseURL(r)
	resp := uiSessionResponse{
		Authenticated:       id != nil,
		ServerName:          s.effectiveServerName(),
		RegistrationEnabled: s.registration.Enabled,
		AuthProxyConfigured: s.trustProxy && s.webUI.AuthProxyHeader != "",
		IsHTTP:              strings.HasPrefix(strings.ToLower(baseURL), "http://"),
		IsLocalhost:         isLoopbackHost(hostName),
	}
	if id == nil {
		return resp, nil
	}

	resp.AuthMode = id.AuthMode
	resp.ProxySubject = id.ProxySubject
	resp.IsLegacy = id.IsLegacy

	if id.IsLegacy {
		resp.FeedURL = baseURL + "/pins.json"
		resp.PinsAPIURL = baseURL + "/api/v1/pins"
		return resp, nil
	}

	meta := s.userMetadata(baseURL, id.User)
	resp.User = &meta
	resp.FeedURL = meta.FeedURL
	resp.PinsAPIURL = meta.PinsAPIURL

	if s.users != nil {
		_, _, avail, err := s.users.RevealTokens(id.User.ID)
		if err != nil {
			return uiSessionResponse{}, err
		}
		resp.TokensAvailable = avail
	}

	return resp, nil
}

func (s *Server) handleUISession(w http.ResponseWriter, r *http.Request) {
	s.setUINoStoreHeaders(w)
	id, errStatus, errCode, errMsg := s.resolveUIIdentity(r)
	if errStatus != 0 {
		writeError(w, r, errStatus, errCode, errMsg)
		return
	}
	if id != nil {
		if rec, ok := w.(*statusRecorder); ok {
			rec.role = RoleWrite
		}
	}

	payload, err := s.buildSessionResponse(r, id)
	if err != nil {
		s.logger.Error("ui_session_build_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to build session state.")
		return
	}
	writeJSON(w, r, http.StatusOK, payload)
}

type uiLoginRequest struct {
	Token string `json:"token"`
}

func (s *Server) handleUILogin(w http.ResponseWriter, r *http.Request) {
	s.setUINoStoreHeaders(w)

	var req uiLoginRequest
	if !decodeStrictJSON(w, r, maxUIPayloadBytes, &req) {
		return
	}
	token := strings.TrimSpace(req.Token)
	if token == "" {
		writeError(w, r, http.StatusBadRequest, "token_required", "Write API token is required.")
		return
	}

	presented := sha256.Sum256([]byte(token))

	// Check per-user write token first (constant-time scan), then global admin write token.
	var matchedUser store.UserSummary
	var userFound bool
	if s.users != nil {
		matchedUser, userFound = s.users.FindUserByWriteToken(presented)
	}
	globalRole := s.auth.AuthenticateRaw(token)

	var id *uiIdentity
	switch {
	case userFound:
		st, err := s.users.PinStore(matchedUser.ID)
		if err != nil {
			s.logger.Error("ui_login_user_store_failed", "userId", matchedUser.ID, "err", err)
			writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to load user pin store.")
			return
		}
		sessID, err := s.sessions.Create("user_token", matchedUser.ID)
		if err != nil {
			s.logger.Error("ui_login_session_failed", "err", err)
			writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to create session.")
			return
		}
		s.setSessionCookie(w, r, sessID)
		id = &uiIdentity{
			AuthMode:  "user_token",
			SessionID: sessID,
			User:      matchedUser,
			Store:     st,
		}
	case globalRole == RoleWrite:
		sessID, err := s.sessions.Create("admin_token", "")
		if err != nil {
			s.logger.Error("ui_login_session_failed", "err", err)
			writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to create session.")
			return
		}
		s.setSessionCookie(w, r, sessID)
		id = &uiIdentity{
			AuthMode:  "admin_token",
			SessionID: sessID,
			IsLegacy:  true,
			Store:     s.store,
		}
	case globalRole == RoleRead:
		writeError(w, r, http.StatusForbidden, "read_only_token", "A read-only token cannot sign into the Web UI; use your write API token.")
		return
	default:
		writeError(w, r, http.StatusUnauthorized, "invalid_token", "Invalid write API token.")
		return
	}

	if rec, ok := w.(*statusRecorder); ok {
		rec.role = RoleWrite
	}

	payload, err := s.buildSessionResponse(r, id)
	if err != nil {
		s.logger.Error("ui_login_response_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to build session state.")
		return
	}
	writeJSON(w, r, http.StatusOK, payload)
}

func (s *Server) handleUILogout(w http.ResponseWriter, r *http.Request) {
	s.setUINoStoreHeaders(w)
	if c, err := r.Cookie(uiSessionCookieName); err == nil && c.Value != "" {
		s.sessions.Delete(c.Value)
	}
	s.clearSessionCookie(w, r)
	w.WriteHeader(http.StatusNoContent)
}

type uiProfileUpdateRequest struct {
	Name string `json:"name"`
}

func (s *Server) handleUIUpdateProfile(w http.ResponseWriter, r *http.Request, id *uiIdentity) {
	s.setUINoStoreHeaders(w)
	if id.IsLegacy || s.users == nil {
		writeError(w, r, http.StatusBadRequest, "unsupported_in_legacy_mode", "Feed name editing is only available for multi-user accounts.")
		return
	}

	var req uiProfileUpdateRequest
	if !decodeStrictJSON(w, r, maxUIPayloadBytes, &req) {
		return
	}

	updated, err := s.users.UpdateUserName(id.User.ID, req.Name)
	if err != nil {
		var valErrs model.ValidationErrors
		if errors.As(err, &valErrs) {
			writeValidationErrors(w, r, valErrs)
			return
		}
		if errors.Is(err, store.ErrUserNotFound) {
			writeError(w, r, http.StatusNotFound, "user_not_found", "User not found.")
			return
		}
		s.logger.Error("ui_update_profile_failed", "userId", id.User.ID, "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to update feed name.")
		return
	}

	id.User = updated
	payload, err := s.buildSessionResponse(r, id)
	if err != nil {
		s.logger.Error("ui_profile_session_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to build session state.")
		return
	}
	writeJSON(w, r, http.StatusOK, payload)
}

type uiPairingResponse struct {
	Available  bool   `json:"available"`
	PairingURI string `json:"pairingUri,omitempty"`
	QRDataURI  string `json:"qrDataUri,omitempty"`
}

func (s *Server) handleUIPairing(w http.ResponseWriter, r *http.Request, id *uiIdentity) {
	s.setUINoStoreHeaders(w)
	if id.IsLegacy || s.users == nil {
		writeError(w, r, http.StatusBadRequest, "unsupported_in_legacy_mode", "Pairing QR retrieval is only available for multi-user accounts.")
		return
	}

	readTok, _, avail, err := s.users.RevealTokens(id.User.ID)
	if err != nil {
		if errors.Is(err, store.ErrUserNotFound) {
			writeError(w, r, http.StatusNotFound, "user_not_found", "User not found.")
			return
		}
		s.logger.Error("ui_pairing_reveal_failed", "userId", id.User.ID, "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to retrieve pairing QR code.")
		return
	}

	resp := uiPairingResponse{
		Available: avail,
	}
	if avail {
		baseURL, _ := s.resolveBaseURL(r)
		meta := s.userMetadata(baseURL, id.User)
		displayName := id.User.Name
		if displayName == "" {
			displayName = s.effectiveServerName()
		}
		pairingURI := BuildPairingURI(meta.FeedURL, readTok, displayName)
		qrURI, err := qrDataURI(pairingURI)
		if err != nil {
			s.logger.Error("ui_pairing_qr_failed", "err", err)
			writeError(w, r, http.StatusInternalServerError, "qr_encode_failed", "Failed to generate QR code.")
			return
		}
		resp.PairingURI = pairingURI
		resp.QRDataURI = qrURI
	}

	writeJSON(w, r, http.StatusOK, resp)
}

type uiTokensResponse struct {
	Available  bool   `json:"available"`
	UserID     string `json:"userId,omitempty"`
	FeedURL    string `json:"feedUrl,omitempty"`
	PinsAPIURL string `json:"pinsApiUrl,omitempty"`
	ReadToken  string `json:"readToken,omitempty"`
	WriteToken string `json:"writeToken,omitempty"`
	PairingURI string `json:"pairingUri,omitempty"`
	QRDataURI  string `json:"qrDataUri,omitempty"`
}

func (s *Server) handleUIRevealTokens(w http.ResponseWriter, r *http.Request, id *uiIdentity) {
	s.setUINoStoreHeaders(w)
	if id.IsLegacy || s.users == nil {
		writeError(w, r, http.StatusBadRequest, "unsupported_in_legacy_mode", "Token retrieval is only available for multi-user accounts; legacy tokens are configured via server environment variables.")
		return
	}

	readTok, writeTok, avail, err := s.users.RevealTokens(id.User.ID)
	if err != nil {
		if errors.Is(err, store.ErrUserNotFound) {
			writeError(w, r, http.StatusNotFound, "user_not_found", "User not found.")
			return
		}
		s.logger.Error("ui_reveal_tokens_failed", "userId", id.User.ID, "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to retrieve tokens.")
		return
	}

	baseURL, _ := s.resolveBaseURL(r)
	meta := s.userMetadata(baseURL, id.User)
	resp := uiTokensResponse{
		Available:  avail,
		UserID:     id.User.ID,
		FeedURL:    meta.FeedURL,
		PinsAPIURL: meta.PinsAPIURL,
	}
	if avail {
		displayName := id.User.Name
		if displayName == "" {
			displayName = s.effectiveServerName()
		}
		pairingURI := BuildPairingURI(meta.FeedURL, readTok, displayName)
		qrURI, err := qrDataURI(pairingURI)
		if err != nil {
			s.logger.Error("ui_reveal_qr_failed", "err", err)
			writeError(w, r, http.StatusInternalServerError, "qr_encode_failed", "Failed to generate QR code.")
			return
		}
		resp.ReadToken = readTok
		resp.WriteToken = writeTok
		resp.PairingURI = pairingURI
		resp.QRDataURI = qrURI
	}

	writeJSON(w, r, http.StatusOK, resp)
}

type uiRotateTokensRequest struct {
	RotateRead  bool `json:"rotateRead"`
	RotateWrite bool `json:"rotateWrite"`
}

func (s *Server) handleUIRotateTokens(w http.ResponseWriter, r *http.Request, id *uiIdentity) {
	s.setUINoStoreHeaders(w)
	if id.IsLegacy || s.users == nil {
		writeError(w, r, http.StatusBadRequest, "unsupported_in_legacy_mode", "Token rotation is only available for multi-user accounts.")
		return
	}

	var req uiRotateTokensRequest
	if !decodeStrictJSON(w, r, maxUIPayloadBytes, &req) {
		return
	}
	if !req.RotateRead && !req.RotateWrite {
		writeError(w, r, http.StatusBadRequest, "invalid_rotation", "Select at least one token (read or write) to rotate.")
		return
	}

	readTok, writeTok, err := s.users.RotateTokens(id.User.ID, req.RotateRead, req.RotateWrite)
	if err != nil {
		if errors.Is(err, store.ErrUserNotFound) {
			writeError(w, r, http.StatusNotFound, "user_not_found", "User not found.")
			return
		}
		s.logger.Error("ui_rotate_tokens_failed", "userId", id.User.ID, "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to rotate tokens.")
		return
	}

	if req.RotateWrite && s.sessions != nil {
		s.sessions.InvalidateUserExcept(id.User.ID, id.SessionID)
	}

	baseURL, _ := s.resolveBaseURL(r)
	meta := s.userMetadata(baseURL, id.User)
	displayName := id.User.Name
	if displayName == "" {
		displayName = s.effectiveServerName()
	}
	pairingURI := BuildPairingURI(meta.FeedURL, readTok, displayName)
	qrURI, err := qrDataURI(pairingURI)
	if err != nil {
		s.logger.Error("ui_rotate_qr_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "qr_encode_failed", "Failed to generate QR code.")
		return
	}

	writeJSON(w, r, http.StatusOK, uiTokensResponse{
		Available:  true,
		UserID:     id.User.ID,
		FeedURL:    meta.FeedURL,
		PinsAPIURL: meta.PinsAPIURL,
		ReadToken:  readTok,
		WriteToken: writeTok,
		PairingURI: pairingURI,
		QRDataURI:  qrURI,
	})
}

func (s *Server) handleUIListPins(w http.ResponseWriter, r *http.Request, id *uiIdentity) {
	s.setUINoStoreHeaders(w)
	includeExpired := strings.EqualFold(r.URL.Query().Get("includeExpired"), "true")
	feed := id.Store.List(includeExpired)
	writeJSON(w, r, http.StatusOK, feed)
}

func (s *Server) handleUICreatePin(w http.ResponseWriter, r *http.Request, id *uiIdentity) {
	s.setUINoStoreHeaders(w)
	s.serveCreatePin(w, r, id.Store, "/ui/api/pins/")
}

func (s *Server) handleUIUpsertPin(w http.ResponseWriter, r *http.Request, id *uiIdentity) {
	s.setUINoStoreHeaders(w)
	s.serveUpsertPin(w, r, id.Store, "/ui/api/pins/")
}

func (s *Server) handleUIDeletePin(w http.ResponseWriter, r *http.Request, id *uiIdentity) {
	s.setUINoStoreHeaders(w)
	s.serveDeletePin(w, r, id.Store)
}

func decodeStrictJSON(w http.ResponseWriter, r *http.Request, maxBytes int64, dst any) bool {
	if ct := strings.TrimSpace(r.Header.Get("Content-Type")); ct != "" {
		mediaType, _, err := mime.ParseMediaType(ct)
		if err != nil || mediaType != "application/json" {
			writeError(w, r, http.StatusUnsupportedMediaType, "unsupported_media_type", "Content-Type must be application/json.")
			return false
		}
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeError(w, r, http.StatusRequestEntityTooLarge, "payload_too_large", "Request body exceeds maximum allowed size.")
			return false
		}
		writeError(w, r, http.StatusBadRequest, "invalid_body", "Unable to read request body.")
		return false
	}

	if len(bytes.TrimSpace(raw)) == 0 {
		writeError(w, r, http.StatusBadRequest, "empty_body", "Request body must contain a JSON object.")
		return false
	}

	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		writeError(w, r, http.StatusBadRequest, "invalid_json", "Invalid JSON payload: "+err.Error())
		return false
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		writeError(w, r, http.StatusBadRequest, "invalid_json", "Request body must contain a single JSON object.")
		return false
	}
	return true
}

func qrDataURI(payload string) (string, error) {
	code, err := qrcode.Encode(payload)
	if err != nil {
		return "", err
	}
	svg := code.SVG(4)
	return "data:image/svg+xml;base64," + base64.StdEncoding.EncodeToString([]byte(svg)), nil
}

func randomHex32() (string, error) {
	var buf [32]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf[:]), nil
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		isDigit := c >= '0' && c <= '9'
		isHex := (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
		if !isDigit && !isHex {
			return false
		}
	}
	return true
}
