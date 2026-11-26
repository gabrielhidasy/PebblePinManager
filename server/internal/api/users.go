package api

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/model"
	"github.com/gabrielhidasy/PebblePinManager/server/internal/store"
)

const maxUserRequestBodyBytes = 4 * 1024

type createUserRequest struct {
	Name string `json:"name"`
}

type userMetadataResponse struct {
	ID         string `json:"id"`
	Name       string `json:"name,omitempty"`
	CreatedAt  string `json:"createdAt"`
	FeedURL    string `json:"feedUrl"`
	PinsAPIURL string `json:"pinsApiUrl"`
}

type userCreatedResponse struct {
	userMetadataResponse
	ReadToken  string `json:"readToken"`
	WriteToken string `json:"writeToken"`
	PairingURI string `json:"pairingUri"`
}

type listUsersResponse struct {
	Users []userMetadataResponse `json:"users"`
}

type userHandlerFunc func(w http.ResponseWriter, r *http.Request, userID string, st *store.Store)

func (s *Server) requireUserAuth(minRole Role, next userHandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := r.PathValue("userId")
		authRes := s.auth.AuthenticateForUser(r, userID, s.users)

		if rec, ok := w.(*statusRecorder); ok {
			if authRes.isGlobalRead {
				rec.role = RoleRead
			} else {
				rec.role = authRes.role
			}
		}

		if authRes.isAdmin {
			if valErr := model.ValidateUserID(userID); valErr != nil {
				writeValidationErrors(w, r, model.ValidationErrors{*valErr})
				return
			}
			st, err := s.users.PinStore(userID)
			if err != nil {
				if errors.Is(err, store.ErrUserNotFound) {
					writeError(w, r, http.StatusNotFound, "user_not_found", "User not found.")
					return
				}
				s.logger.Error("load_user_store_failed", "userId", userID, "err", err)
				writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to load user pin store.")
				return
			}
			next(w, r, userID, st)
			return
		}

		if authRes.isGlobalRead {
			writeError(w, r, http.StatusForbidden, "forbidden", "Global read token cannot access per-user feeds; use a user-scoped token.")
			return
		}

		if authRes.role == RoleNone {
			w.Header().Set("WWW-Authenticate", `Bearer realm="pebble-pin-server"`)
			writeError(w, r, http.StatusUnauthorized, "unauthorized", "Missing or invalid authentication token.")
			return
		}

		if authRes.role < minRole {
			writeError(w, r, http.StatusForbidden, "forbidden", "Token does not have permission for this operation.")
			return
		}

		st, err := s.users.PinStore(userID)
		if err != nil {
			if errors.Is(err, store.ErrUserNotFound) {
				writeError(w, r, http.StatusNotFound, "user_not_found", "User not found.")
				return
			}
			s.logger.Error("load_user_store_failed", "userId", userID, "err", err)
			writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to load user pin store.")
			return
		}

		next(w, r, userID, st)
	}
}

func (s *Server) handleUserFeed(w http.ResponseWriter, r *http.Request, _ string, st *store.Store) {
	s.serveFeed(w, r, st)
}

func (s *Server) handleUserListPins(w http.ResponseWriter, r *http.Request, _ string, st *store.Store) {
	s.serveListPins(w, r, st)
}

func (s *Server) handleUserGetPin(w http.ResponseWriter, r *http.Request, _ string, st *store.Store) {
	s.serveGetPin(w, r, st)
}

func (s *Server) handleUserCreatePin(w http.ResponseWriter, r *http.Request, userID string, st *store.Store) {
	s.serveCreatePin(w, r, st, "/api/v1/users/"+userID+"/pins/")
}

func (s *Server) handleUserUpsertPin(w http.ResponseWriter, r *http.Request, userID string, st *store.Store) {
	s.serveUpsertPin(w, r, st, "/api/v1/users/"+userID+"/pins/")
}

func (s *Server) handleUserDeletePin(w http.ResponseWriter, r *http.Request, _ string, st *store.Store) {
	s.serveDeletePin(w, r, st)
}

func (s *Server) handleListUsers(w http.ResponseWriter, r *http.Request) {
	baseURL, _ := s.resolveBaseURL(r)
	users := s.users.ListUsers()
	out := make([]userMetadataResponse, 0, len(users))
	for _, u := range users {
		out = append(out, s.userMetadata(baseURL, u))
	}
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("Vary", "Authorization, X-API-Key")
	writeJSON(w, r, http.StatusOK, listUsersResponse{Users: out})
}

func (s *Server) handleGetUser(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userId")
	if valErr := model.ValidateUserID(userID); valErr != nil {
		writeValidationErrors(w, r, model.ValidationErrors{*valErr})
		return
	}
	u, ok := s.users.GetUser(userID)
	if !ok {
		writeError(w, r, http.StatusNotFound, "user_not_found", "User not found.")
		return
	}
	baseURL, _ := s.resolveBaseURL(r)
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("Vary", "Authorization, X-API-Key")
	writeJSON(w, r, http.StatusOK, s.userMetadata(baseURL, u))
}

func (s *Server) handleCreateUser(w http.ResponseWriter, r *http.Request) {
	reqPayload, ok := s.decodeCreateUserJSON(w, r)
	if !ok {
		return
	}

	created, err := s.users.CreateUser(reqPayload.Name)
	if err != nil {
		s.writeCreateUserError(w, r, err)
		return
	}

	baseURL, _ := s.resolveBaseURL(r)
	resp := s.buildUserCreatedResponse(baseURL, created)

	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("Location", "/api/v1/users/"+created.User.ID)
	writeJSON(w, r, http.StatusCreated, resp)
}

func (s *Server) handleDeleteUser(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userId")
	if valErr := model.ValidateUserID(userID); valErr != nil {
		writeValidationErrors(w, r, model.ValidationErrors{*valErr})
		return
	}

	if err := s.users.DeleteUser(userID); err != nil {
		if errors.Is(err, store.ErrUserNotFound) {
			writeError(w, r, http.StatusNotFound, "user_not_found", "User not found.")
			return
		}
		s.logger.Error("delete_user_failed", "userId", userID, "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to delete user.")
		return
	}
	if s.sessions != nil {
		s.sessions.InvalidateUser(userID)
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) decodeCreateUserJSON(w http.ResponseWriter, r *http.Request) (createUserRequest, bool) {
	if ct := strings.TrimSpace(r.Header.Get("Content-Type")); ct != "" {
		mediaType, _, err := mime.ParseMediaType(ct)
		if err != nil || mediaType != "application/json" {
			writeError(w, r, http.StatusUnsupportedMediaType, "unsupported_media_type", "Content-Type must be application/json.")
			return createUserRequest{}, false
		}
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxUserRequestBodyBytes)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeError(w, r, http.StatusRequestEntityTooLarge, "payload_too_large", "Request body exceeds maximum allowed size.")
			return createUserRequest{}, false
		}
		writeError(w, r, http.StatusBadRequest, "invalid_body", "Unable to read request body.")
		return createUserRequest{}, false
	}

	if len(bytes.TrimSpace(raw)) == 0 {
		return createUserRequest{}, true
	}

	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()

	var payload createUserRequest
	if err := dec.Decode(&payload); err != nil {
		writeError(w, r, http.StatusBadRequest, "invalid_json", "Invalid JSON payload: "+err.Error())
		return createUserRequest{}, false
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		writeError(w, r, http.StatusBadRequest, "invalid_json", "Request body must contain a single JSON object.")
		return createUserRequest{}, false
	}
	return payload, true
}

func (s *Server) writeCreateUserError(w http.ResponseWriter, r *http.Request, err error) {
	var valErrs model.ValidationErrors
	if errors.As(err, &valErrs) {
		writeValidationErrors(w, r, valErrs)
		return
	}
	if errors.Is(err, store.ErrUserLimitReached) {
		writeError(w, r, http.StatusForbidden, "user_limit_reached", "Maximum number of registered users has been reached.")
		return
	}
	s.logger.Error("create_user_failed", "err", err)
	writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to register user.")
}

func (s *Server) userMetadata(baseURL string, u store.UserSummary) userMetadataResponse {
	return userMetadataResponse{
		ID:         u.ID,
		Name:       u.Name,
		CreatedAt:  u.CreatedAt,
		FeedURL:    baseURL + "/" + u.ID + "/pins.json",
		PinsAPIURL: baseURL + "/api/v1/users/" + u.ID + "/pins",
	}
}

func (s *Server) buildUserCreatedResponse(baseURL string, created store.CreatedUser) userCreatedResponse {
	meta := s.userMetadata(baseURL, created.User)
	displayName := created.User.Name
	if displayName == "" {
		displayName = s.effectiveServerName()
	}
	return userCreatedResponse{
		userMetadataResponse: meta,
		ReadToken:            created.ReadToken,
		WriteToken:           created.WriteToken,
		PairingURI:           BuildPairingURI(meta.FeedURL, created.ReadToken, displayName),
	}
}
