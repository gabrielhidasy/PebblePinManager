package api

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/model"
	"github.com/gabrielhidasy/PebblePinManager/server/internal/store"
)

const maxRequestBodyBytes = 64 * 1024

// ServerOption configures optional Server behavior.
type ServerOption func(*Server)

// WithLogger sets the structured logger used for request logging and error reporting.
func WithLogger(logger *slog.Logger) ServerOption {
	return func(s *Server) {
		if logger != nil {
			s.logger = logger
		}
	}
}

// WithTrustProxy enables reading client IPs from X-Forwarded-For / X-Real-IP in access logs.
func WithTrustProxy(trust bool) ServerOption {
	return func(s *Server) {
		s.trustProxy = trust
	}
}

// WithServerClock overrides the clock used during request validation (useful in tests).
func WithServerClock(now func() time.Time) ServerOption {
	return func(s *Server) {
		if now != nil {
			s.now = now
		}
	}
}

// Server exposes the authenticated Pebble pin feed and CRUD HTTP API.
type Server struct {
	store        *store.Store
	auth         *Authenticator
	logger       *slog.Logger
	trustProxy   bool
	registration RegistrationConfig
	now          func() time.Time
	mux          *http.ServeMux
}

// NewServer constructs a configured HTTP handler for the companion pin server.
func NewServer(st *store.Store, auth *Authenticator, opts ...ServerOption) *Server {
	s := &Server{
		store:  st,
		auth:   auth,
		logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		now:    time.Now,
		mux:    http.NewServeMux(),
	}
	for _, opt := range opts {
		opt(s)
	}

	s.registerRoutes()
	return s
}

func (s *Server) registerRoutes() {
	s.mux.HandleFunc("GET /healthz", s.handleHealthz)
	s.mux.HandleFunc("HEAD /healthz", s.handleHealthz)

	if s.registration.Enabled {
		s.mux.HandleFunc("GET /register", s.handleRegister)
		s.mux.HandleFunc("HEAD /register", s.handleRegister)
	}

	s.mux.HandleFunc("GET /pins.json", s.requireAuth(RoleRead, s.handleFeed))
	s.mux.HandleFunc("HEAD /pins.json", s.requireAuth(RoleRead, s.handleFeed))
	s.mux.HandleFunc("GET /api/v1/pins.json", s.requireAuth(RoleRead, s.handleFeed))
	s.mux.HandleFunc("HEAD /api/v1/pins.json", s.requireAuth(RoleRead, s.handleFeed))

	s.mux.HandleFunc("GET /api/v1/pins", s.requireAuth(RoleRead, s.handleListPins))
	s.mux.HandleFunc("HEAD /api/v1/pins", s.requireAuth(RoleRead, s.handleListPins))
	s.mux.HandleFunc("POST /api/v1/pins", s.requireAuth(RoleWrite, s.handleCreatePin))

	s.mux.HandleFunc("GET /api/v1/pins/{id}", s.requireAuth(RoleRead, s.handleGetPin))
	s.mux.HandleFunc("HEAD /api/v1/pins/{id}", s.requireAuth(RoleRead, s.handleGetPin))
	s.mux.HandleFunc("PUT /api/v1/pins/{id}", s.requireAuth(RoleWrite, s.handleUpsertPin))
	s.mux.HandleFunc("DELETE /api/v1/pins/{id}", s.requireAuth(RoleWrite, s.handleDeletePin))
}

// ServeHTTP implements http.Handler with security headers and token-safe access logging.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}

	rec.Header().Set("X-Content-Type-Options", "nosniff")
	rec.Header().Set("Referrer-Policy", "no-referrer")
	rec.Header().Set("X-Frame-Options", "DENY")

	s.mux.ServeHTTP(rec, r)

	// Never log headers, Authorization, X-API-Key, or raw tokens.
	s.logger.Info("http_request",
		"method", r.Method,
		"path", r.URL.Path,
		"status", rec.status,
		"bytes", rec.bytes,
		"durationMs", time.Since(start).Milliseconds(),
		"remoteIp", s.clientIP(r),
		"role", rec.role.String(),
	)
}

func (s *Server) requireAuth(minRole Role, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		role := s.auth.Authenticate(r)
		if rec, ok := w.(*statusRecorder); ok {
			rec.role = role
		}
		if role == RoleNone {
			w.Header().Set("WWW-Authenticate", `Bearer realm="pebble-pin-server"`)
			writeError(w, r, http.StatusUnauthorized, "unauthorized", "Missing or invalid authentication token.")
			return
		}
		if role < minRole {
			writeError(w, r, http.StatusForbidden, "forbidden", "Token does not have permission for this operation.")
			return
		}
		next(w, r)
	}
}

func (s *Server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, r, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleFeed(w http.ResponseWriter, r *http.Request) {
	snap, err := s.store.Feed()
	if err != nil {
		s.logger.Error("feed_snapshot_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to build pin feed.")
		return
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("ETag", snap.ETag)
	w.Header().Set("Last-Modified", snap.LastModified.UTC().Format(http.TimeFormat))
	w.Header().Set("Cache-Control", "private, no-cache")
	w.Header().Set("Vary", "Authorization, X-API-Key")

	if isNotModified(r, snap.ETag, snap.LastModified) {
		w.WriteHeader(http.StatusNotModified)
		return
	}

	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(snap.JSON)
	}
}

func (s *Server) handleListPins(w http.ResponseWriter, r *http.Request) {
	includeExpired := strings.EqualFold(r.URL.Query().Get("includeExpired"), "true")
	if !includeExpired {
		s.handleFeed(w, r)
		return
	}

	feed := s.store.List(true)
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("Vary", "Authorization, X-API-Key")
	writeJSON(w, r, http.StatusOK, feed)
}

func (s *Server) handleGetPin(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := model.ValidateID(id); err != nil {
		writeValidationErrors(w, r, model.ValidationErrors{*err})
		return
	}

	pin, ok := s.store.Get(id)
	if !ok {
		writeError(w, r, http.StatusNotFound, "not_found", "Pin not found.")
		return
	}

	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("Vary", "Authorization, X-API-Key")
	writeJSON(w, r, http.StatusOK, pin)
}

func (s *Server) handleCreatePin(w http.ResponseWriter, r *http.Request) {
	pin, ok := s.decodePinPayload(w, r)
	if !ok {
		return
	}

	if strings.TrimSpace(pin.ID) == "" {
		generated, err := model.GeneratePinID()
		if err != nil {
			s.logger.Error("generate_pin_id_failed", "err", err)
			writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to generate pin ID.")
			return
		}
		pin.ID = generated
	}

	normalized, valErrs := model.NormalizeAndValidate(pin, s.now())
	if len(valErrs) > 0 {
		writeValidationErrors(w, r, valErrs)
		return
	}

	created, err := s.store.Create(normalized)
	if err != nil {
		if errors.Is(err, store.ErrAlreadyExists) {
			writeError(w, r, http.StatusConflict, "already_exists", "A pin with this ID already exists; use PUT /api/v1/pins/{id} to update it.")
			return
		}
		s.logger.Error("create_pin_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to persist pin.")
		return
	}

	w.Header().Set("Location", "/api/v1/pins/"+created.ID)
	writeJSON(w, r, http.StatusCreated, created)
}

func (s *Server) handleUpsertPin(w http.ResponseWriter, r *http.Request) {
	pathID := r.PathValue("id")
	if idErr := model.ValidateID(pathID); idErr != nil {
		writeValidationErrors(w, r, model.ValidationErrors{*idErr})
		return
	}

	pin, ok := s.decodePinPayload(w, r)
	if !ok {
		return
	}

	if bodyID := strings.TrimSpace(pin.ID); bodyID != "" && bodyID != pathID {
		writeValidationErrors(w, r, model.ValidationErrors{{
			Field:   "id",
			Code:    "id_mismatch",
			Message: "Pin ID in request body must match the ID in the URL path.",
		}})
		return
	}
	pin.ID = pathID

	normalized, valErrs := model.NormalizeAndValidate(pin, s.now())
	if len(valErrs) > 0 {
		writeValidationErrors(w, r, valErrs)
		return
	}

	stored, created, err := s.store.Upsert(normalized)
	if err != nil {
		s.logger.Error("upsert_pin_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to persist pin.")
		return
	}

	status := http.StatusOK
	if created {
		status = http.StatusCreated
		w.Header().Set("Location", "/api/v1/pins/"+stored.ID)
	}
	writeJSON(w, r, status, stored)
}

func (s *Server) handleDeletePin(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if idErr := model.ValidateID(id); idErr != nil {
		writeValidationErrors(w, r, model.ValidationErrors{*idErr})
		return
	}

	if err := s.store.Delete(id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, r, http.StatusNotFound, "not_found", "Pin not found.")
			return
		}
		s.logger.Error("delete_pin_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to delete pin.")
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) decodePinPayload(w http.ResponseWriter, r *http.Request) (model.Pin, bool) {
	if ct := strings.TrimSpace(r.Header.Get("Content-Type")); ct != "" {
		mediaType, _, err := mime.ParseMediaType(ct)
		if err != nil || mediaType != "application/json" {
			writeError(w, r, http.StatusUnsupportedMediaType, "unsupported_media_type", "Content-Type must be application/json.")
			return model.Pin{}, false
		}
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBodyBytes)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeError(w, r, http.StatusRequestEntityTooLarge, "payload_too_large", "Request body exceeds maximum allowed size.")
			return model.Pin{}, false
		}
		writeError(w, r, http.StatusBadRequest, "invalid_body", "Unable to read request body.")
		return model.Pin{}, false
	}

	if len(bytes.TrimSpace(raw)) == 0 {
		writeError(w, r, http.StatusBadRequest, "empty_body", "Request body must contain a JSON pin object.")
		return model.Pin{}, false
	}

	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()

	var pin model.Pin
	if err := dec.Decode(&pin); err != nil {
		writeError(w, r, http.StatusBadRequest, "invalid_json", "Invalid JSON payload: "+err.Error())
		return model.Pin{}, false
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		writeError(w, r, http.StatusBadRequest, "invalid_json", "Request body must contain a single JSON object.")
		return model.Pin{}, false
	}

	return pin, true
}

func isNotModified(r *http.Request, etag string, lastModified time.Time) bool {
	if inm := strings.TrimSpace(r.Header.Get("If-None-Match")); inm != "" {
		return etagMatches(inm, etag)
	}

	if ims := strings.TrimSpace(r.Header.Get("If-Modified-Since")); ims != "" {
		if parsed, err := http.ParseTime(ims); err == nil {
			return !lastModified.UTC().Truncate(time.Second).After(parsed.UTC())
		}
	}
	return false
}

// etagMatches evaluates If-None-Match using RFC 9110 weak comparison.
func etagMatches(headerValue, currentETag string) bool {
	currentOpaque := stripWeakPrefix(strings.TrimSpace(currentETag))
	for _, candidate := range strings.Split(headerValue, ",") {
		tag := strings.TrimSpace(candidate)
		if tag == "*" {
			return true
		}
		if stripWeakPrefix(tag) == currentOpaque {
			return true
		}
	}
	return false
}

func stripWeakPrefix(etag string) string {
	return strings.TrimPrefix(etag, "W/")
}

func (s *Server) clientIP(r *http.Request) string {
	if s.trustProxy {
		if xff := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); xff != "" {
			if first, _, ok := strings.Cut(xff, ","); ok {
				return strings.TrimSpace(first)
			}
			return xff
		}
		if xri := strings.TrimSpace(r.Header.Get("X-Real-IP")); xri != "" {
			return xri
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

type apiErrorResponse struct {
	Error   string                 `json:"error"`
	Message string                 `json:"message"`
	Details model.ValidationErrors `json:"details,omitempty"`
}

func writeError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	writeJSON(w, r, status, apiErrorResponse{
		Error:   code,
		Message: message,
	})
}

func writeValidationErrors(w http.ResponseWriter, r *http.Request, details model.ValidationErrors) {
	writeJSON(w, r, http.StatusBadRequest, apiErrorResponse{
		Error:   "validation_failed",
		Message: "One or more pin fields are invalid.",
		Details: details,
	})
}

func writeJSON(w http.ResponseWriter, r *http.Request, status int, payload any) {
	data, err := json.Marshal(payload)
	if err != nil {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusInternalServerError)
		if r.Method != http.MethodHead {
			_, _ = w.Write([]byte(`{"error":"internal_error","message":"failed to serialize response"}` + "\n"))
		}
		return
	}
	data = append(data, '\n')
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if r.Method != http.MethodHead {
		_, _ = w.Write(data)
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
	role   Role
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(p []byte) (int, error) {
	n, err := r.ResponseWriter.Write(p)
	r.bytes += n
	return n, err
}
