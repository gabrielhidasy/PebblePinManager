package model

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	// SchemaVersion is the shared JSON pin feed schema version consumed by Pin Manager for Pebble (#95).
	SchemaVersion = 1

	DefaultIconURI    = "system://images/TIMELINE_CALENDAR"
	DefaultLayoutType = LayoutGenericPin

	MaxIDLength          = 128
	MaxTitleLength       = 128
	MaxDescriptionLength = 512
	MinDurationMinutes   = 1
	MaxDurationMinutes   = 1440

	// Pebble Timeline accepts pins from 2 days in the past up to 365 days in the future.
	MaxPastOffset   = 48 * time.Hour
	MaxFutureOffset = 365 * 24 * time.Hour
)

const (
	LayoutGenericPin          = "GENERIC_PIN"
	LayoutCalendarPin         = "CALENDAR_PIN"
	LayoutGenericReminder     = "GENERIC_REMINDER"
	LayoutGenericNotification = "GENERIC_NOTIFICATION"
)

var (
	validIDRegex       = regexp.MustCompile(`^[A-Za-z0-9._:@-]+$`)
	validIconURIRegex  = regexp.MustCompile(`^system://images/[A-Z0-9_]+$`)
	validHexColorRegex = regexp.MustCompile(`^#?[0-9A-Fa-f]{6}$`)

	validLayoutTypes = map[string]struct{}{
		LayoutGenericPin:          {},
		LayoutCalendarPin:         {},
		LayoutGenericReminder:     {},
		LayoutGenericNotification: {},
	}
)

// Pin represents a Pebble Timeline pin in the shared JSON feed schema.
type Pin struct {
	ID              string  `json:"id"`
	Title           string  `json:"title"`
	Description     *string `json:"description,omitempty"`
	StartTime       string  `json:"startTime"`
	DurationMinutes *int    `json:"durationMinutes,omitempty"`
	IconURI         string  `json:"iconUri"`
	BackgroundColor *string `json:"backgroundColor,omitempty"`
	PrimaryColor    *string `json:"primaryColor,omitempty"`
	SecondaryColor  *string `json:"secondaryColor,omitempty"`
	LayoutType      string  `json:"layoutType"`
}

// PinFeed is the top-level versioned JSON document served at /pins.json and /api/v1/pins.json.
type PinFeed struct {
	SchemaVersion int    `json:"schemaVersion"`
	UpdatedAt     string `json:"updatedAt"`
	Pins          []Pin  `json:"pins"`
}

// ValidationError describes a single validation problem on a pin payload.
type ValidationError struct {
	Field   string `json:"field"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

// ValidationErrors is a collection of pin field validation errors.
type ValidationErrors []ValidationError

func (e ValidationErrors) Error() string {
	if len(e) == 0 {
		return ""
	}
	msgs := make([]string, len(e))
	for i, item := range e {
		msgs[i] = fmt.Sprintf("%s: %s", item.Field, item.Message)
	}
	return strings.Join(msgs, "; ")
}

// GeneratePinID generates a random stable pin identifier matching TimelineEvent.generatePinId ("ppm-<12-hex>").
func GeneratePinID() (string, error) {
	var buf [6]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", fmt.Errorf("generate pin id: %w", err)
	}
	return "ppm-" + hex.EncodeToString(buf[:]), nil
}

// ValidateID checks whether a pin ID is non-empty, bounded, and safe for URL paths.
func ValidateID(id string) *ValidationError {
	trimmed := strings.TrimSpace(id)
	if trimmed == "" {
		return &ValidationError{
			Field:   "id",
			Code:    "id_blank",
			Message: "Pin ID must not be blank.",
		}
	}
	if n := utf8.RuneCountInString(trimmed); n > MaxIDLength {
		return &ValidationError{
			Field:   "id",
			Code:    "id_too_long",
			Message: fmt.Sprintf("Pin ID is too long (%d/%d characters).", n, MaxIDLength),
		}
	}
	if !validIDRegex.MatchString(trimmed) {
		return &ValidationError{
			Field:   "id",
			Code:    "id_invalid",
			Message: "Pin ID may only contain letters, digits, '.', '_', ':', '@', and '-'.",
		}
	}
	return nil
}

// NormalizeAndValidate trims fields, applies schema defaults, and validates against PebblePinManager TimelineEvent constraints.
func NormalizeAndValidate(in Pin, now time.Time) (Pin, ValidationErrors) {
	out := Pin{
		ID:              strings.TrimSpace(in.ID),
		Title:           strings.TrimSpace(in.Title),
		Description:     trimOptionalString(in.Description),
		StartTime:       strings.TrimSpace(in.StartTime),
		DurationMinutes: copyOptionalInt(in.DurationMinutes),
		IconURI:         strings.TrimSpace(in.IconURI),
		BackgroundColor: trimOptionalString(in.BackgroundColor),
		PrimaryColor:    trimOptionalString(in.PrimaryColor),
		SecondaryColor:  trimOptionalString(in.SecondaryColor),
		LayoutType:      strings.TrimSpace(in.LayoutType),
	}

	if out.IconURI == "" {
		out.IconURI = DefaultIconURI
	}
	if out.LayoutType == "" {
		out.LayoutType = DefaultLayoutType
	}

	var errs ValidationErrors

	if idErr := ValidateID(out.ID); idErr != nil {
		errs = append(errs, *idErr)
	}

	titleLen := utf8.RuneCountInString(out.Title)
	if titleLen == 0 {
		errs = append(errs, ValidationError{
			Field:   "title",
			Code:    "title_blank",
			Message: "Event title is required.",
		})
	} else if titleLen > MaxTitleLength {
		errs = append(errs, ValidationError{
			Field:   "title",
			Code:    "title_too_long",
			Message: fmt.Sprintf("Title is too long (%d/%d characters).", titleLen, MaxTitleLength),
		})
	}

	if out.Description != nil {
		if descLen := utf8.RuneCountInString(*out.Description); descLen > MaxDescriptionLength {
			errs = append(errs, ValidationError{
				Field:   "description",
				Code:    "description_too_long",
				Message: fmt.Sprintf("Description exceeds the Pebble Timeline limit (%d/%d characters).", descLen, MaxDescriptionLength),
			})
		}
	}

	if out.StartTime == "" {
		errs = append(errs, ValidationError{
			Field:   "startTime",
			Code:    "start_time_required",
			Message: "Event startTime is required and must be a valid ISO-8601 / RFC3339 timestamp.",
		})
	} else {
		parsedTime, err := ParseTimestamp(out.StartTime)
		if err != nil {
			errs = append(errs, ValidationError{
				Field:   "startTime",
				Code:    "start_time_invalid",
				Message: "startTime must be a valid ISO-8601 / RFC3339 timestamp with timezone (e.g. 2026-11-26T14:00:00Z).",
			})
		} else {
			nowUTC := now.UTC()
			earliest := nowUTC.Add(-MaxPastOffset)
			latest := nowUTC.Add(MaxFutureOffset)
			if parsedTime.Before(earliest) {
				errs = append(errs, ValidationError{
					Field:   "startTime",
					Code:    "timestamp_too_far_in_past",
					Message: "Pebble Timeline only accepts pins up to 2 days in the past.",
				})
			} else if parsedTime.After(latest) {
				errs = append(errs, ValidationError{
					Field:   "startTime",
					Code:    "timestamp_too_far_in_future",
					Message: "Pebble Timeline only accepts pins up to 1 year in the future.",
				})
			} else {
				out.StartTime = parsedTime.UTC().Format(time.RFC3339)
			}
		}
	}

	if out.DurationMinutes != nil {
		d := *out.DurationMinutes
		if d < MinDurationMinutes || d > MaxDurationMinutes {
			errs = append(errs, ValidationError{
				Field:   "durationMinutes",
				Code:    "duration_out_of_range",
				Message: fmt.Sprintf("Duration must be between %d and %d minutes (got %d).", MinDurationMinutes, MaxDurationMinutes, d),
			})
		}
	}

	if !validIconURIRegex.MatchString(out.IconURI) {
		errs = append(errs, ValidationError{
			Field:   "iconUri",
			Code:    "invalid_icon_uri",
			Message: fmt.Sprintf("Pin icon URI must match system://images/<ICON_NAME> (got '%s').", out.IconURI),
		})
	}

	if out.BackgroundColor != nil {
		normalized, ok := normalizeHexColor(*out.BackgroundColor)
		if !ok {
			errs = append(errs, ValidationError{
				Field:   "backgroundColor",
				Code:    "invalid_background_color",
				Message: fmt.Sprintf("Background color must be a 6-digit hex color like #FF5500 (got '%s').", *out.BackgroundColor),
			})
		} else {
			out.BackgroundColor = &normalized
		}
	}

	if out.PrimaryColor != nil {
		normalized, ok := normalizeHexColor(*out.PrimaryColor)
		if !ok {
			errs = append(errs, ValidationError{
				Field:   "primaryColor",
				Code:    "invalid_primary_color",
				Message: fmt.Sprintf("Primary color must be a 6-digit hex color like #FFFFFF (got '%s').", *out.PrimaryColor),
			})
		} else {
			out.PrimaryColor = &normalized
		}
	}

	if out.SecondaryColor != nil {
		normalized, ok := normalizeHexColor(*out.SecondaryColor)
		if !ok {
			errs = append(errs, ValidationError{
				Field:   "secondaryColor",
				Code:    "invalid_secondary_color",
				Message: fmt.Sprintf("Secondary color must be a 6-digit hex color like #AAAAAA (got '%s').", *out.SecondaryColor),
			})
		} else {
			out.SecondaryColor = &normalized
		}
	}

	if _, ok := validLayoutTypes[out.LayoutType]; !ok {
		errs = append(errs, ValidationError{
			Field:   "layoutType",
			Code:    "invalid_layout_type",
			Message: fmt.Sprintf("layoutType must be one of GENERIC_PIN, CALENDAR_PIN, GENERIC_REMINDER, GENERIC_NOTIFICATION (got '%s').", out.LayoutType),
		})
	}

	return out, errs
}

// ParseTimestamp parses an RFC3339 or RFC3339Nano timestamp into UTC.
func ParseTimestamp(raw string) (time.Time, error) {
	t, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil {
		return time.Time{}, err
	}
	return t.UTC(), nil
}

// EndTime returns the pin's end timestamp (startTime + durationMinutes if present, otherwise startTime).
func (p Pin) EndTime() (time.Time, error) {
	start, err := ParseTimestamp(p.StartTime)
	if err != nil {
		return time.Time{}, err
	}
	if p.DurationMinutes != nil && *p.DurationMinutes > 0 {
		return start.Add(time.Duration(*p.DurationMinutes) * time.Minute), nil
	}
	return start, nil
}

func trimOptionalString(v *string) *string {
	if v == nil {
		return nil
	}
	trimmed := strings.TrimSpace(*v)
	if trimmed == "" {
		return nil
	}
	return &trimmed
}

func copyOptionalInt(v *int) *int {
	if v == nil {
		return nil
	}
	c := *v
	return &c
}

func normalizeHexColor(raw string) (string, bool) {
	trimmed := strings.TrimSpace(raw)
	if !validHexColorRegex.MatchString(trimmed) {
		return "", false
	}
	hexPart := strings.TrimPrefix(trimmed, "#")
	return "#" + strings.ToUpper(hexPart), true
}
