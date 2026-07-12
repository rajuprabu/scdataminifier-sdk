import Foundation

/// Viewer-side reconstruction of an SCDataMinifier IMAGE value into a complete
/// WEBP/AVIF file, byte-identical to the Java `model.ScImage` +
/// `util.ImageContainers` build path. Viewers only rebuild (never strip), so
/// this is all the iOS side needs. Pure Swift, no dependencies.
///
/// Usage:
///   let img = try ScImage.parse(valueBytes)      // the IMAGE tlv/cell/variable value
///   let fileBytes = try img.toImageBytes()       // complete .webp/.avif
///   let uiImage = UIImage(data: Data(fileBytes)) // iOS 16+ decodes WEBP & AVIF natively
public enum ScImageError: Error { case empty, unknownType(Int), unsupportedVersion(Int) }

public struct ScImage {

    public enum ImageType: Int { case webp = 0, avif = 1 }

    public let type: ImageType
    public let headerPresent: Bool
    public let version: Int
    public let data: [UInt8]

    /// Parse an IMAGE value (image-header byte + data).
    public static func parse(_ value: [UInt8]) throws -> ScImage {
        guard let first = value.first else { throw ScImageError.empty }
        let b = Int(first)
        guard let type = ImageType(rawValue: b & 0x0F) else { throw ScImageError.unknownType(b & 0x0F) }
        let headerPresent = (b & 0x10) != 0
        let version = (b >> 5) & 0x07
        return ScImage(type: type, headerPresent: headerPresent, version: version,
                       data: Array(value[1...]))
    }

    /// Complete standard image file bytes (container rebuilt when stripped).
    public func toImageBytes() throws -> [UInt8] {
        if headerPresent { return data }
        guard version == 1 else { throw ScImageError.unsupportedVersion(version) }
        return type == .webp ? Self.buildWebpV1(data) : Self.buildAvifV1(data)
    }

    // MARK: - WebP v1

    static func buildWebpV1(_ payload: [UInt8]) -> [UInt8] {
        let pad = payload.count & 1
        var out = [UInt8]()
        out.append(contentsOf: ascii("RIFF"))
        out.append(contentsOf: le32(4 + 8 + payload.count + pad))
        out.append(contentsOf: ascii("WEBP"))
        out.append(contentsOf: ascii("VP8 "))
        out.append(contentsOf: le32(payload.count))
        out.append(contentsOf: payload)
        if pad == 1 { out.append(0) }
        return out
    }

    // MARK: - AVIF v1

    // Fixed boxes exactly as produced by libavif for a single 8-bit 4:2:0
    // (AV1 Main profile, brand MA1B) sRGB image.
    private static let FTYP = hex("00000020667479706176696600000000617669666d6966316d6961664d413142")
    private static let HDLR = hex("0000002168646c7200000000000000007069637400000000000000000000000000")
    private static let PITM = hex("0000000e7069746d000000000001")
    private static let IINF = hex("0000002869696e660000000000010000001a696e6665020000000001000061763031436f6c6f7200")
    private static let PIXI = hex("00000010706978690000000003080808")
    private static let COLR = hex("00000013636f6c726e636c780001000d000680")
    private static let IPMA = hex("0000001769706d61000000000000000100010401028304")

    static func buildAvifV1(_ data: [UInt8]) -> [UInt8] {
        var p = 0
        func u16() -> Int { let v = (Int(data[p]) << 8) | Int(data[p + 1]); p += 2; return v }
        let width = u16(); let height = u16()
        let av1cLen = Int(data[p]); p += 1
        let av1c = Array(data[p ..< p + av1cLen]); p += av1cLen
        let payload = Array(data[p...])

        let ispe = box("ispe", [hex("00000000"), u32be(width), u32be(height)])
        let av1C = box("av1C", [av1c])
        let ipco = box("ipco", [ispe, PIXI, av1C, COLR])
        let iprp = box("iprp", [ipco, IPMA])

        let metaSize = 12 + HDLR.count + PITM.count + 30 + IINF.count + iprp.count
        let shellSize = FTYP.count + metaSize + 8
        let ilocFixed = hex("0000001e696c6f630000000044000001000100000001")

        var out = [UInt8]()
        out.append(contentsOf: FTYP)
        out.append(contentsOf: u32be(metaSize)); out.append(contentsOf: ascii("meta"))
        out.append(contentsOf: hex("00000000"))
        out.append(contentsOf: HDLR); out.append(contentsOf: PITM)
        out.append(contentsOf: ilocFixed)
        out.append(contentsOf: u32be(shellSize)); out.append(contentsOf: u32be(payload.count))
        out.append(contentsOf: IINF); out.append(contentsOf: iprp)
        out.append(contentsOf: u32be(8 + payload.count)); out.append(contentsOf: ascii("mdat"))
        out.append(contentsOf: payload)
        return out
    }

    // MARK: - helpers

    private static func ascii(_ s: String) -> [UInt8] { Array(s.utf8) }

    private static func le32(_ v: Int) -> [UInt8] {
        [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
    }

    private static func u32be(_ v: Int) -> [UInt8] {
        [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
    }

    private static func box(_ type: String, _ parts: [[UInt8]]) -> [UInt8] {
        var len = 8
        for part in parts { len += part.count }
        var out = u32be(len)
        out.append(contentsOf: ascii(type))
        for part in parts { out.append(contentsOf: part) }
        return out
    }

    private static func hex(_ s: String) -> [UInt8] {
        var out = [UInt8]()
        var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            out.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return out
    }
}
