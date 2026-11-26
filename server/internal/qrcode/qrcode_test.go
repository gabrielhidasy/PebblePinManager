package qrcode

import (
	"encoding/xml"
	"errors"
	"strings"
	"testing"
)

func TestFormatAndVersionBits(t *testing.T) {
	// ISO/IEC 18004 Annex C: EC Level M (00), Mask 0 -> 0x5412 ^ 0x0000 = 0x5412.
	if got := formatBitsForMask(0); got != 0x5412 {
		t.Errorf("formatBitsForMask(0) = 0x%04X, want 0x5412", got)
	}
	// ISO/IEC 18004 Annex C: EC Level M (00), Mask 4 -> 0x455F? Let's verify BCH(0b00100):
	// data=4 (00100), (4<<10)=0x1000. 0x1000 ^ (0x537<<2=0x14DC) = 0x04DC; 0x04DC ^ 0x537 = 0x01EB.
	// (0x1000 | 0x01EB) ^ 0x5412 = 0x11EB ^ 0x5412 = 0x45F9.
	if got := formatBitsForMask(4); got != 0x45F9 {
		t.Errorf("formatBitsForMask(4) = 0x%04X, want 0x45F9", got)
	}

	// ISO/IEC 18004 Annex D: Version 7 -> 0x07C94.
	if got := versionBitsForVersion(7); got != 0x07C94 {
		t.Errorf("versionBitsForVersion(7) = 0x%05X, want 0x07C94", got)
	}
	// ISO/IEC 18004 Annex D: Version 10 -> 0x0A4D3.
	if got := versionBitsForVersion(10); got != 0x0A4D3 {
		t.Errorf("versionBitsForVersion(10) = 0x%05X, want 0x0A4D3", got)
	}
}

func TestVersionSelectionAndCapacities(t *testing.T) {
	cases := []struct {
		name        string
		length      int
		wantVersion int
	}{
		{"v1 short", 14, 1},
		{"v2 boundary", 15, 2},
		{"v7 version info boundary", 120, 7},
		{"v9 8-bit count max", 180, 9},
		{"v10 16-bit count", 181, 10},
		{"v10 typical pairing uri", 200, 10},
		{"v20 max capacity", 666, 20},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			payload := strings.Repeat("a", tc.length)
			code, err := Encode(payload)
			if err != nil {
				t.Fatalf("Encode(%d bytes) failed: %v", tc.length, err)
			}
			if code.Version != tc.wantVersion {
				t.Errorf("version = %d, want %d", code.Version, tc.wantVersion)
			}
			wantSize := 17 + 4*tc.wantVersion
			if code.Size != wantSize {
				t.Errorf("size = %d, want %d", code.Size, wantSize)
			}

			// Verify finder patterns (7x7 outer ring dark, 5x5 inner ring light, 3x3 center dark).
			for _, origin := range [][2]int{{0, 0}, {0, code.Size - 7}, {code.Size - 7, 0}} {
				for dr := 0; dr < 7; dr++ {
					for dc := 0; dc < 7; dc++ {
						wantDark := dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4)
						if got := code.At(origin[0]+dr, origin[1]+dc); got != wantDark {
							t.Fatalf("finder at (%d,%d) offset (%d,%d) = %v, want %v",
								origin[0], origin[1], dr, dc, got, wantDark)
						}
					}
				}
			}

			// Verify dark module at (size-8, 8).
			if !code.At(code.Size-8, 8) {
				t.Errorf("dark module at (%d, 8) is false", code.Size-8)
			}

			// Verify timing patterns between separators.
			for i := 8; i < code.Size-8; i++ {
				want := i%2 == 0
				if code.At(6, i) != want {
					t.Errorf("horizontal timing (6,%d) = %v, want %v", i, code.At(6, i), want)
				}
				if code.At(i, 6) != want {
					t.Errorf("vertical timing (%d,6) = %v, want %v", i, code.At(i, 6), want)
				}
			}
		})
	}
}

func TestEncode_TooLong(t *testing.T) {
	_, err := Encode(strings.Repeat("x", 700))
	if !errors.Is(err, ErrDataTooLong) {
		t.Fatalf("expected ErrDataTooLong, got %v", err)
	}
}

func TestSVG_ValidXMLAndSelfContained(t *testing.T) {
	uri := "pebblepin://pair?v=1&url=https%3A%2F%2Fpins.example.com%2Fpins.json&auth=bearer&token=0123456789abcdef0123456789abcdef&name=Home+Server"
	code, err := Encode(uri)
	if err != nil {
		t.Fatalf("Encode: %v", err)
	}

	svg := code.SVG(4)
	if !strings.HasPrefix(svg, "<svg ") || !strings.HasSuffix(svg, "</svg>") {
		t.Fatalf("unexpected SVG wrapper: %s", svg[:min(80, len(svg))])
	}
	if strings.Contains(strings.ToLower(svg), "<script") {
		t.Fatalf("SVG must not contain script tags")
	}

	var parsed any
	if err := xml.Unmarshal([]byte(svg), &parsed); err != nil {
		t.Fatalf("SVG is not valid XML: %v", err)
	}
}
