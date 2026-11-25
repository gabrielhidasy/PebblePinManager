package model

import (
	"strings"
	"testing"
	"time"
)

func strPtr(s string) *string { return &s }
func intPtr(i int) *int       { return &i }

func TestNormalizeAndValidate_ValidAndDefaults(t *testing.T) {
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)

	in := Pin{
		ID:              "  ext-meeting-123  ",
		Title:           "  Team Standup  ",
		Description:     strPtr("   "),
		StartTime:       "2026-11-26T11:00:00-03:00",
		DurationMinutes: intPtr(30),
		BackgroundColor: strPtr("00aaff"),
		PrimaryColor:    strPtr("#ff5500"),
	}

	got, errs := NormalizeAndValidate(in, now)
	if len(errs) != 0 {
		t.Fatalf("expected no validation errors, got: %v", errs)
	}

	if got.ID != "ext-meeting-123" {
		t.Errorf("ID = %q, want %q", got.ID, "ext-meeting-123")
	}
	if got.Title != "Team Standup" {
		t.Errorf("Title = %q, want %q", got.Title, "Team Standup")
	}
	if got.Description != nil {
		t.Errorf("Description = %v, want nil for whitespace-only input", *got.Description)
	}
	if got.StartTime != "2026-11-26T14:00:00Z" {
		t.Errorf("StartTime = %q, want UTC RFC3339 %q", got.StartTime, "2026-11-26T14:00:00Z")
	}
	if got.IconURI != DefaultIconURI {
		t.Errorf("IconURI = %q, want default %q", got.IconURI, DefaultIconURI)
	}
	if got.LayoutType != DefaultLayoutType {
		t.Errorf("LayoutType = %q, want default %q", got.LayoutType, DefaultLayoutType)
	}
	if got.BackgroundColor == nil || *got.BackgroundColor != "#00AAFF" {
		t.Errorf("BackgroundColor = %v, want #00AAFF", got.BackgroundColor)
	}
	if got.PrimaryColor == nil || *got.PrimaryColor != "#FF5500" {
		t.Errorf("PrimaryColor = %v, want #FF5500", got.PrimaryColor)
	}
}

func TestNormalizeAndValidate_Errors(t *testing.T) {
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)

	tests := []struct {
		name     string
		pin      Pin
		wantCode string
	}{
		{
			name:     "blank id",
			pin:      Pin{ID: "  ", Title: "Valid", StartTime: "2026-11-26T14:00:00Z"},
			wantCode: "id_blank",
		},
		{
			name:     "invalid id chars",
			pin:      Pin{ID: "bad/id", Title: "Valid", StartTime: "2026-11-26T14:00:00Z"},
			wantCode: "id_invalid",
		},
		{
			name:     "blank title",
			pin:      Pin{ID: "p1", Title: "   ", StartTime: "2026-11-26T14:00:00Z"},
			wantCode: "title_blank",
		},
		{
			name:     "title too long",
			pin:      Pin{ID: "p1", Title: strings.Repeat("a", MaxTitleLength+1), StartTime: "2026-11-26T14:00:00Z"},
			wantCode: "title_too_long",
		},
		{
			name:     "description too long",
			pin:      Pin{ID: "p1", Title: "Valid", Description: strPtr(strings.Repeat("b", MaxDescriptionLength+1)), StartTime: "2026-11-26T14:00:00Z"},
			wantCode: "description_too_long",
		},
		{
			name:     "missing startTime",
			pin:      Pin{ID: "p1", Title: "Valid"},
			wantCode: "start_time_required",
		},
		{
			name:     "invalid startTime",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "not-a-timestamp"},
			wantCode: "start_time_invalid",
		},
		{
			name:     "startTime too far in past",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2026-11-23T11:59:59Z"},
			wantCode: "timestamp_too_far_in_past",
		},
		{
			name:     "startTime too far in future",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2027-11-26T12:00:01Z"},
			wantCode: "timestamp_too_far_in_future",
		},
		{
			name:     "duration below min",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2026-11-26T14:00:00Z", DurationMinutes: intPtr(0)},
			wantCode: "duration_out_of_range",
		},
		{
			name:     "duration above max",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2026-11-26T14:00:00Z", DurationMinutes: intPtr(1441)},
			wantCode: "duration_out_of_range",
		},
		{
			name:     "invalid iconUri",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2026-11-26T14:00:00Z", IconURI: "https://example.com/icon.png"},
			wantCode: "invalid_icon_uri",
		},
		{
			name:     "invalid backgroundColor",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2026-11-26T14:00:00Z", BackgroundColor: strPtr("#FFF")},
			wantCode: "invalid_background_color",
		},
		{
			name:     "invalid primaryColor",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2026-11-26T14:00:00Z", PrimaryColor: strPtr("blue")},
			wantCode: "invalid_primary_color",
		},
		{
			name:     "invalid secondaryColor",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2026-11-26T14:00:00Z", SecondaryColor: strPtr("#ZZZZZZ")},
			wantCode: "invalid_secondary_color",
		},
		{
			name:     "invalid layoutType",
			pin:      Pin{ID: "p1", Title: "Valid", StartTime: "2026-11-26T14:00:00Z", LayoutType: "SPORTS_PIN"},
			wantCode: "invalid_layout_type",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, errs := NormalizeAndValidate(tc.pin, now)
			if len(errs) == 0 {
				t.Fatalf("expected validation error %q, got none", tc.wantCode)
			}
			found := false
			for _, e := range errs {
				if e.Code == tc.wantCode {
					found = true
					break
				}
			}
			if !found {
				t.Errorf("expected error code %q in %+v", tc.wantCode, errs)
			}
		})
	}
}

func TestGeneratePinID(t *testing.T) {
	id1, err := GeneratePinID()
	if err != nil {
		t.Fatalf("GeneratePinID error: %v", err)
	}
	id2, err := GeneratePinID()
	if err != nil {
		t.Fatalf("GeneratePinID error: %v", err)
	}
	if !strings.HasPrefix(id1, "ppm-") || len(id1) != 16 {
		t.Errorf("unexpected generated id format: %q", id1)
	}
	if id1 == id2 {
		t.Errorf("expected unique IDs, got identical %q", id1)
	}
	if err := ValidateID(id1); err != nil {
		t.Errorf("generated ID failed validation: %v", err)
	}
}
