import Foundation
import CoreGraphics

/// Decode-only image SDK (iOS). Turns a stored IMAGE value — the 1-byte descriptor plus the
/// version-1 headerless data carried in an SCDataMinifier TLV — into a `CGImage`, gated by a
/// license. The underlying image format is never named here or in the embedded `scdec.xcframework`;
/// the app embeds whichever flavour (A or B) matches the images it must display.
///
/// The framework exposes the C API declared in `scdec.h` (module `scdec`). Import it and use:
/// ```
/// ScDecoder.license(licData, packageName: Bundle.main.bundleIdentifier!)  // once
/// let cg = ScDecoder.decode(imageValue)                                   // per image
/// ```
public enum ScDecoder {

    @discardableResult
    public static func license(_ license: Data, packageName: String) -> Int {
        license.withUnsafeBytes { raw in
            Int(scdec_license(raw.bindMemory(to: UInt8.self).baseAddress, Int32(license.count), packageName))
        }
    }

    public static var isLicensed: Bool { scdec_licensed() != 0 }

    /// Pixel dimensions of an IMAGE value without decoding, or nil on failure.
    public static func size(_ value: Data) -> (width: Int, height: Int)? {
        var w: Int32 = 0, h: Int32 = 0
        let ok = value.withUnsafeBytes { raw in
            scdec_info(raw.bindMemory(to: UInt8.self).baseAddress, Int32(value.count), &w, &h)
        }
        return ok == 0 ? (Int(w), Int(h)) : nil
    }

    /// Decodes an IMAGE value to an opaque RGB `CGImage`, or nil on failure.
    public static func decode(_ value: Data) -> CGImage? {
        var w: Int32 = 0, h: Int32 = 0
        guard let rgb = value.withUnsafeBytes({ raw in
            scdec_open(raw.bindMemory(to: UInt8.self).baseAddress, Int32(value.count), &w, &h)
        }) else { return nil }
        let width = Int(w), height = Int(h), count = width * height * 3
        defer { scdec_free(rgb) }

        let data = Data(bytes: rgb, count: count)
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        return CGImage(
            width: width, height: height,
            bitsPerComponent: 8, bitsPerPixel: 24, bytesPerRow: width * 3,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}
