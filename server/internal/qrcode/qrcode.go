package qrcode

import (
	"errors"
	"fmt"
	"strings"
)

const (
	minVersion = 1
	maxVersion = 20

	// Format information bits for Error Correction Level M (00) + mask (3 bits).
	ecLevelMBits = 0x00
	formatMask   = 0x5412
	formatPoly   = 0x537
	versionPoly  = 0x1F25
)

// ErrDataTooLong is returned when the payload exceeds the capacity of the supported QR versions.
var ErrDataTooLong = errors.New("qr payload exceeds maximum supported capacity")

// Code represents a generated QR Code module matrix.
type Code struct {
	Version int
	Size    int
	modules []bool
}

// At reports whether the module at (row, col) is dark (true) or light (false).
func (c *Code) At(row, col int) bool {
	if row < 0 || row >= c.Size || col < 0 || col >= c.Size {
		return false
	}
	return c.modules[row*c.Size+col]
}

// SVG renders the QR code as a crisp, self-contained SVG with the given quiet-zone border (in modules).
// Standard ISO/IEC 18004 recommends a border of at least 4 modules.
func (c *Code) SVG(border int) string {
	if border < 0 {
		border = 0
	}
	dim := c.Size + 2*border

	var path strings.Builder
	for r := 0; r < c.Size; r++ {
		y := r + border
		col := 0
		for col < c.Size {
			if !c.At(r, col) {
				col++
				continue
			}
			start := col
			for col < c.Size && c.At(r, col) {
				col++
			}
			runLen := col - start
			fmt.Fprintf(&path, "M%d,%dh%dv1h-%dz", start+border, y, runLen, runLen)
		}
	}

	var out strings.Builder
	fmt.Fprintf(
		&out,
		`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="280" height="280" role="img" aria-label="Server pairing QR code" shape-rendering="crispEdges">`,
		dim,
		dim,
	)
	fmt.Fprintf(&out, `<rect width="%d" height="%d" fill="#ffffff"/>`, dim, dim)
	fmt.Fprintf(&out, `<path fill="#000000" d="%s"/>`, path.String())
	out.WriteString(`</svg>`)
	return out.String()
}

// Encode encodes content as an ISO/IEC 18004 QR Code (Byte mode, Error Correction Level M).
func Encode(content string) (*Code, error) {
	data := []byte(content)
	version, spec, err := selectVersion(len(data))
	if err != nil {
		return nil, err
	}

	dataCodewords := buildDataCodewords(data, version, spec.totalDataCodewords)
	allCodewords := addErrorCorrectionAndInterleave(dataCodewords, spec)

	size := 17 + 4*version
	base := newMatrix(size)
	base.drawFunctionPatterns(version)
	base.drawCodewords(allCodewords)

	bestMask := 0
	bestPenalty := int(^uint(0) >> 1)
	var bestModules []bool

	for mask := 0; mask < 8; mask++ {
		candidate := base.clone()
		candidate.applyMask(mask)
		candidate.drawFormatBits(mask)
		if version >= 7 {
			candidate.drawVersionBits(version)
		}
		score := candidate.penaltyScore()
		if score < bestPenalty {
			bestPenalty = score
			bestMask = mask
			bestModules = candidate.modules
		}
	}
	_ = bestMask

	return &Code{
		Version: version,
		Size:    size,
		modules: bestModules,
	}, nil
}

type versionSpec struct {
	totalDataCodewords int
	ecPerBlock         int
	g1Blocks           int
	g1Data             int
	g2Blocks           int
	g2Data             int
}

// ISO/IEC 18004 Table 9 for Versions 1..20, Error Correction Level M.
var versionSpecsM = [maxVersion + 1]versionSpec{
	0:  {},
	1:  {totalDataCodewords: 16, ecPerBlock: 10, g1Blocks: 1, g1Data: 16},
	2:  {totalDataCodewords: 28, ecPerBlock: 16, g1Blocks: 1, g1Data: 28},
	3:  {totalDataCodewords: 44, ecPerBlock: 26, g1Blocks: 1, g1Data: 44},
	4:  {totalDataCodewords: 64, ecPerBlock: 18, g1Blocks: 2, g1Data: 32},
	5:  {totalDataCodewords: 86, ecPerBlock: 24, g1Blocks: 2, g1Data: 43},
	6:  {totalDataCodewords: 108, ecPerBlock: 16, g1Blocks: 4, g1Data: 27},
	7:  {totalDataCodewords: 124, ecPerBlock: 18, g1Blocks: 4, g1Data: 31},
	8:  {totalDataCodewords: 154, ecPerBlock: 22, g1Blocks: 2, g1Data: 38, g2Blocks: 2, g2Data: 39},
	9:  {totalDataCodewords: 182, ecPerBlock: 22, g1Blocks: 3, g1Data: 36, g2Blocks: 2, g2Data: 37},
	10: {totalDataCodewords: 216, ecPerBlock: 26, g1Blocks: 4, g1Data: 43, g2Blocks: 1, g2Data: 44},
	11: {totalDataCodewords: 254, ecPerBlock: 30, g1Blocks: 1, g1Data: 50, g2Blocks: 4, g2Data: 51},
	12: {totalDataCodewords: 290, ecPerBlock: 22, g1Blocks: 6, g1Data: 36, g2Blocks: 2, g2Data: 37},
	13: {totalDataCodewords: 334, ecPerBlock: 22, g1Blocks: 8, g1Data: 37, g2Blocks: 1, g2Data: 38},
	14: {totalDataCodewords: 365, ecPerBlock: 24, g1Blocks: 4, g1Data: 40, g2Blocks: 5, g2Data: 41},
	15: {totalDataCodewords: 415, ecPerBlock: 24, g1Blocks: 5, g1Data: 41, g2Blocks: 5, g2Data: 42},
	16: {totalDataCodewords: 453, ecPerBlock: 28, g1Blocks: 7, g1Data: 45, g2Blocks: 3, g2Data: 46},
	17: {totalDataCodewords: 507, ecPerBlock: 28, g1Blocks: 10, g1Data: 46, g2Blocks: 1, g2Data: 47},
	18: {totalDataCodewords: 563, ecPerBlock: 26, g1Blocks: 9, g1Data: 43, g2Blocks: 4, g2Data: 44},
	19: {totalDataCodewords: 627, ecPerBlock: 26, g1Blocks: 3, g1Data: 44, g2Blocks: 11, g2Data: 45},
	20: {totalDataCodewords: 669, ecPerBlock: 26, g1Blocks: 3, g1Data: 41, g2Blocks: 13, g2Data: 42},
}

// ISO/IEC 18004 Annex E alignment pattern center coordinates for Versions 1..20.
var alignmentCoords = [maxVersion + 1][]int{
	1:  nil,
	2:  {6, 18},
	3:  {6, 22},
	4:  {6, 26},
	5:  {6, 30},
	6:  {6, 34},
	7:  {6, 22, 38},
	8:  {6, 24, 42},
	9:  {6, 26, 46},
	10: {6, 28, 50},
	11: {6, 30, 54},
	12: {6, 32, 58},
	13: {6, 34, 62},
	14: {6, 26, 46, 66},
	15: {6, 26, 48, 70},
	16: {6, 26, 50, 74},
	17: {6, 30, 54, 78},
	18: {6, 30, 56, 82},
	19: {6, 30, 58, 86},
	20: {6, 34, 62, 90},
}

func charCountBits(version int) int {
	if version <= 9 {
		return 8
	}
	return 16
}

func selectVersion(byteLen int) (int, versionSpec, error) {
	for v := minVersion; v <= maxVersion; v++ {
		spec := versionSpecsM[v]
		capacityBits := spec.totalDataCodewords * 8
		requiredBits := 4 + charCountBits(v) + byteLen*8
		if requiredBits <= capacityBits {
			return v, spec, nil
		}
	}
	return 0, versionSpec{}, fmt.Errorf("%w (%d bytes)", ErrDataTooLong, byteLen)
}

type bitBuffer struct {
	bits []bool
}

func (b *bitBuffer) appendBits(val uint32, length int) {
	for i := length - 1; i >= 0; i-- {
		b.bits = append(b.bits, ((val>>uint(i))&1) != 0)
	}
}

func buildDataCodewords(data []byte, version int, totalDataCodewords int) []byte {
	var bb bitBuffer
	// Mode indicator: Byte mode = 0100.
	bb.appendBits(0x4, 4)
	bb.appendBits(uint32(len(data)), charCountBits(version))
	for _, b := range data {
		bb.appendBits(uint32(b), 8)
	}

	capacityBits := totalDataCodewords * 8
	// Terminator (up to 4 zero bits).
	termLen := capacityBits - len(bb.bits)
	if termLen > 4 {
		termLen = 4
	}
	bb.appendBits(0, termLen)

	// Pad to next byte boundary.
	if rem := len(bb.bits) % 8; rem != 0 {
		bb.appendBits(0, 8-rem)
	}

	out := make([]byte, 0, totalDataCodewords)
	for i := 0; i < len(bb.bits); i += 8 {
		var v byte
		for j := 0; j < 8; j++ {
			v <<= 1
			if bb.bits[i+j] {
				v |= 1
			}
		}
		out = append(out, v)
	}

	// Alternate pad bytes 0xEC, 0x11.
	padToggle := true
	for len(out) < totalDataCodewords {
		if padToggle {
			out = append(out, 0xEC)
		} else {
			out = append(out, 0x11)
		}
		padToggle = !padToggle
	}
	return out
}

func addErrorCorrectionAndInterleave(data []byte, spec versionSpec) []byte {
	totalBlocks := spec.g1Blocks + spec.g2Blocks
	dataBlocks := make([][]byte, 0, totalBlocks)
	ecBlocks := make([][]byte, 0, totalBlocks)
	gen := rsGeneratorPoly(spec.ecPerBlock)

	offset := 0
	for i := 0; i < spec.g1Blocks; i++ {
		blk := data[offset : offset+spec.g1Data]
		offset += spec.g1Data
		dataBlocks = append(dataBlocks, blk)
		ecBlocks = append(ecBlocks, rsRemainder(blk, gen))
	}
	for i := 0; i < spec.g2Blocks; i++ {
		blk := data[offset : offset+spec.g2Data]
		offset += spec.g2Data
		dataBlocks = append(dataBlocks, blk)
		ecBlocks = append(ecBlocks, rsRemainder(blk, gen))
	}

	maxDataLen := spec.g1Data
	if spec.g2Blocks > 0 && spec.g2Data > maxDataLen {
		maxDataLen = spec.g2Data
	}

	result := make([]byte, 0, spec.totalDataCodewords+totalBlocks*spec.ecPerBlock)
	for i := 0; i < maxDataLen; i++ {
		for _, blk := range dataBlocks {
			if i < len(blk) {
				result = append(result, blk[i])
			}
		}
	}
	for i := 0; i < spec.ecPerBlock; i++ {
		for _, ec := range ecBlocks {
			result = append(result, ec[i])
		}
	}
	return result
}

// gfMultiply multiplies two elements in GF(2^8) with primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D).
func gfMultiply(x, y byte) byte {
	var z byte
	for i := 7; i >= 0; i-- {
		var carry byte
		if (z & 0x80) != 0 {
			carry = 0x1D
		}
		z = (z << 1) ^ carry
		if ((y >> uint(i)) & 1) != 0 {
			z ^= x
		}
	}
	return z
}

// rsGeneratorPoly returns the Reed-Solomon generator polynomial coefficients (excluding the leading 1).
func rsGeneratorPoly(degree int) []byte {
	poly := make([]byte, degree)
	poly[degree-1] = 1
	root := byte(1)
	for i := 0; i < degree; i++ {
		for j := 0; j < degree; j++ {
			poly[j] = gfMultiply(poly[j], root)
			if j+1 < degree {
				poly[j] ^= poly[j+1]
			}
		}
		root = gfMultiply(root, 0x02)
	}
	return poly
}

func rsRemainder(data, gen []byte) []byte {
	rem := make([]byte, len(gen))
	for _, b := range data {
		factor := b ^ rem[0]
		copy(rem, rem[1:])
		rem[len(rem)-1] = 0
		for i, coef := range gen {
			rem[i] ^= gfMultiply(coef, factor)
		}
	}
	return rem
}

type matrix struct {
	size       int
	modules    []bool
	isFunction []bool
}

func newMatrix(size int) *matrix {
	return &matrix{
		size:       size,
		modules:    make([]bool, size*size),
		isFunction: make([]bool, size*size),
	}
}

func (m *matrix) clone() *matrix {
	mods := make([]bool, len(m.modules))
	copy(mods, m.modules)
	// isFunction is never mutated after drawFunctionPatterns.
	return &matrix{
		size:       m.size,
		modules:    mods,
		isFunction: m.isFunction,
	}
}

func (m *matrix) setFunction(r, c int, dark bool) {
	if r < 0 || r >= m.size || c < 0 || c >= m.size {
		return
	}
	idx := r*m.size + c
	m.modules[idx] = dark
	m.isFunction[idx] = true
}

func (m *matrix) at(r, c int) bool {
	return m.modules[r*m.size+c]
}

func (m *matrix) drawFunctionPatterns(version int) {
	// Timing patterns.
	for i := 0; i < m.size; i++ {
		dark := i%2 == 0
		m.setFunction(6, i, dark)
		m.setFunction(i, 6, dark)
	}

	// Finder patterns + separators.
	m.drawFinderPattern(3, 3)
	m.drawFinderPattern(3, m.size-4)
	m.drawFinderPattern(m.size-4, 3)

	// Alignment patterns.
	coords := alignmentCoords[version]
	for i, r := range coords {
		for j, c := range coords {
			if (i == 0 && j == 0) || (i == 0 && j == len(coords)-1) || (i == len(coords)-1 && j == 0) {
				continue
			}
			m.drawAlignmentPattern(r, c)
		}
	}

	// Reserve format info modules & dark module.
	m.drawFormatBits(0)
	if version >= 7 {
		m.drawVersionBits(version)
	}
}

func (m *matrix) drawFinderPattern(centerR, centerC int) {
	for dr := -4; dr <= 4; dr++ {
		for dc := -4; dc <= 4; dc++ {
			dist := absInt(dr)
			if ad := absInt(dc); ad > dist {
				dist = ad
			}
			r, c := centerR+dr, centerC+dc
			if r >= 0 && r < m.size && c >= 0 && c < m.size {
				dark := dist != 2 && dist != 4
				m.setFunction(r, c, dark)
			}
		}
	}
}

func (m *matrix) drawAlignmentPattern(centerR, centerC int) {
	for dr := -2; dr <= 2; dr++ {
		for dc := -2; dc <= 2; dc++ {
			dist := absInt(dr)
			if ad := absInt(dc); ad > dist {
				dist = ad
			}
			m.setFunction(centerR+dr, centerC+dc, dist != 1)
		}
	}
}

func formatBitsForMask(mask int) uint32 {
	data := uint32((ecLevelMBits << 3) | (mask & 0x7))
	rem := data
	for i := 0; i < 10; i++ {
		rem = (rem << 1) ^ ((rem >> 9) * formatPoly)
	}
	return ((data << 10) | rem) ^ formatMask
}

func versionBitsForVersion(version int) uint32 {
	rem := uint32(version)
	for i := 0; i < 12; i++ {
		rem = (rem << 1) ^ ((rem >> 11) * versionPoly)
	}
	return (uint32(version) << 12) | rem
}

func (m *matrix) drawFormatBits(mask int) {
	bits := formatBitsForMask(mask)

	// Copy 1: around top-left finder.
	for i := 0; i <= 5; i++ {
		m.setFunction(i, 8, ((bits>>uint(i))&1) != 0)
	}
	m.setFunction(7, 8, ((bits>>6)&1) != 0)
	m.setFunction(8, 8, ((bits>>7)&1) != 0)
	m.setFunction(8, 7, ((bits>>8)&1) != 0)
	for i := 9; i < 15; i++ {
		m.setFunction(8, 14-i, ((bits>>uint(i))&1) != 0)
	}

	// Copy 2: top-right and bottom-left.
	for i := 0; i < 8; i++ {
		m.setFunction(8, m.size-1-i, ((bits>>uint(i))&1) != 0)
	}
	for i := 8; i < 15; i++ {
		m.setFunction(m.size-15+i, 8, ((bits>>uint(i))&1) != 0)
	}
	// Fixed dark module.
	m.setFunction(m.size-8, 8, true)
}

func (m *matrix) drawVersionBits(version int) {
	bits := versionBitsForVersion(version)
	for i := 0; i < 18; i++ {
		dark := ((bits >> uint(i)) & 1) != 0
		a := m.size - 11 + (i % 3)
		b := i / 3
		m.setFunction(a, b, dark)
		m.setFunction(b, a, dark)
	}
}

func (m *matrix) drawCodewords(data []byte) {
	bitIdx := 0
	totalBits := len(data) * 8

	for right := m.size - 1; right >= 1; right -= 2 {
		if right == 6 {
			right = 5
		}
		upward := ((right + 1) & 2) == 0
		for vert := 0; vert < m.size; vert++ {
			r := vert
			if upward {
				r = m.size - 1 - vert
			}
			for j := 0; j < 2; j++ {
				c := right - j
				idx := r*m.size + c
				if m.isFunction[idx] {
					continue
				}
				var dark bool
				if bitIdx < totalBits {
					dark = ((data[bitIdx>>3] >> uint(7-(bitIdx&7))) & 1) != 0
					bitIdx++
				}
				m.modules[idx] = dark
			}
		}
	}
}

func (m *matrix) applyMask(mask int) {
	for r := 0; r < m.size; r++ {
		for c := 0; c < m.size; c++ {
			idx := r*m.size + c
			if m.isFunction[idx] {
				continue
			}
			var invert bool
			switch mask {
			case 0:
				invert = (r+c)%2 == 0
			case 1:
				invert = r%2 == 0
			case 2:
				invert = c%3 == 0
			case 3:
				invert = (r+c)%3 == 0
			case 4:
				invert = ((r/2)+(c/3))%2 == 0
			case 5:
				invert = (r*c)%2+(r*c)%3 == 0
			case 6:
				invert = ((r*c)%2+(r*c)%3)%2 == 0
			case 7:
				invert = ((r+c)%2+(r*c)%3)%2 == 0
			}
			if invert {
				m.modules[idx] = !m.modules[idx]
			}
		}
	}
}

// penaltyScore evaluates the 4 ISO/IEC 18004 mask penalty rules (N1..N4).
func (m *matrix) penaltyScore() int {
	score := 0

	// N1 + N3 on rows.
	for r := 0; r < m.size; r++ {
		runColor := false
		runLen := 0
		var hist [7]int
		for c := 0; c < m.size; c++ {
			dark := m.at(r, c)
			if dark == runColor {
				runLen++
				if runLen == 5 {
					score += 3
				} else if runLen > 5 {
					score++
				}
			} else {
				finderAddHistory(runLen, &hist)
				if !runColor {
					score += finderCountPatterns(hist) * 40
				}
				runColor = dark
				runLen = 1
			}
		}
		score += finderTerminateAndCount(runColor, runLen, &hist, m.size) * 40
	}

	// N1 + N3 on columns.
	for c := 0; c < m.size; c++ {
		runColor := false
		runLen := 0
		var hist [7]int
		for r := 0; r < m.size; r++ {
			dark := m.at(r, c)
			if dark == runColor {
				runLen++
				if runLen == 5 {
					score += 3
				} else if runLen > 5 {
					score++
				}
			} else {
				finderAddHistory(runLen, &hist)
				if !runColor {
					score += finderCountPatterns(hist) * 40
				}
				runColor = dark
				runLen = 1
			}
		}
		score += finderTerminateAndCount(runColor, runLen, &hist, m.size) * 40
	}

	// N2: 2x2 blocks of identical color.
	for r := 0; r < m.size-1; r++ {
		for c := 0; c < m.size-1; c++ {
			col := m.at(r, c)
			if col == m.at(r, c+1) && col == m.at(r+1, c) && col == m.at(r+1, c+1) {
				score += 3
			}
		}
	}

	// N4: balance of dark and light modules.
	darkCount := 0
	for _, d := range m.modules {
		if d {
			darkCount++
		}
	}
	total := m.size * m.size
	k := (absInt(darkCount*20-total*10)+total-1)/total - 1
	if k > 0 {
		score += k * 10
	}

	return score
}

func finderAddHistory(currentRunLen int, hist *[7]int) {
	if hist[0] == 0 {
		currentRunLen += 0
	}
	copy(hist[1:], hist[:6])
	hist[0] = currentRunLen
}

func finderTerminateAndCount(currentRunColor bool, currentRunLen int, hist *[7]int, size int) int {
	if currentRunColor {
		finderAddHistory(currentRunLen, hist)
		currentRunLen = 0
	}
	currentRunLen += size
	finderAddHistory(currentRunLen, hist)
	return finderCountPatterns(*hist)
}

func finderCountPatterns(hist [7]int) int {
	n := hist[1]
	if n <= 0 {
		return 0
	}
	core := hist[2] == n && hist[3] == 3*n && hist[4] == n && hist[5] == n
	if !core {
		return 0
	}
	count := 0
	if hist[0] >= 4*n && hist[6] >= n {
		count++
	}
	if hist[6] >= 4*n && hist[0] >= n {
		count++
	}
	return count
}

func absInt(v int) int {
	if v < 0 {
		return -v
	}
	return v
}
