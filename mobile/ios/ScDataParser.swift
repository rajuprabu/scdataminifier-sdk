import Foundation
import Compression
import zlib

/// Swift port of the SCDataMinifier payload reader (viewer side). Parses a
/// payload into header + contents (TLV / tables / variables) + signature
/// info, faithful to the Java `ScDataParser`. Encryption and signature
/// verification are injected as closures (the SDK never holds keys).
///
/// Compression: ZIP (raw DEFLATE) and GZIP are handled with Apple's
/// Compression framework; ZIP_DICT calls the optional `dictionaryDecompress`
/// closure (preset dictionaries need zlib, which the app can supply).

// MARK: - Errors

public enum ScDataError: Error, CustomStringConvertible {
    case truncated(need: Int, at: Int, have: Int)
    case badMagic(Int)
    case unsupportedVersion(Int)
    case unknown(String)
    public var description: String {
        switch self {
        case .truncated(let n, let at, let have): return "Unexpected end of data: need \(n) at \(at), have \(have)"
        case .badMagic(let m): return "Bad magic byte 0x\(String(m, radix: 16))"
        case .unsupportedVersion(let v): return "Unsupported format version \(v)"
        case .unknown(let s): return s
        }
    }
}

// MARK: - Enums

public enum TagType: Int {
    case caption = 0, string = 1, integer = 2, float = 3, image = 4, biometric = 5, table = 6, unicodeString = 7
    static func from(_ c: Int) throws -> TagType {
        guard let t = TagType(rawValue: c) else { throw ScDataError.unknown("Unknown tag type \(c)") }
        return t
    }
}

public enum ContentType: Int {
    case variables = 0, tlv = 1, compressedTlv = 2
    static func from(_ c: Int) throws -> ContentType {
        guard let t = ContentType(rawValue: c) else { throw ScDataError.unknown("Unknown content type \(c)") }
        return t
    }
}

public enum CompressionType: Int { case zip = 0, gzip = 1, zipDict = 2 }

public enum SignatureAlgorithm: Int {
    case ecdsaP256Sha256 = 0, rsa2048Sha256 = 1, ecdsaP384Sha384 = 2, none = 7
    static func from(_ c: Int) throws -> SignatureAlgorithm {
        guard let t = SignatureAlgorithm(rawValue: c) else { throw ScDataError.unknown("Unknown signature algorithm \(c)") }
        return t
    }
}

// MARK: - Model

public struct ScHeader {
    public let version, applicationId: Int
    public let uniqueId: UInt64
    public let minClientVersion: Int
    public let encrypted: Bool
    public let encryptionTypeCode, encryptionKeyVersion: Int
}

public struct Tlv {
    public let type: TagType
    public let tagId: Int?          // nil when absent
    public let value: [UInt8]       // raw value (empty for table)
    public let table: TableData?    // set only for .table

    public func asString() -> String {
        switch type {
        case .caption, .unicodeString: return String(decoding: value, as: UTF8.self)
        case .string: return String(bytes: value, encoding: .isoLatin1) ?? ""
        default: return ""
        }
    }
    public func asInt() -> Int32 {
        Int32(bitPattern: (UInt32(value[0]) << 24) | (UInt32(value[1]) << 16) | (UInt32(value[2]) << 8) | UInt32(value[3]))
    }
    public func asFloat() -> Float { Float(bitPattern: UInt32(bitPattern: asInt())) }
    public func asImage() throws -> ScImage { try ScImage.parse(value) }
}

public struct TableCellData {
    public let type: TagType
    public let value: [UInt8]      // empty means "no cell"
    public let present: Bool
}

public struct TableData {
    public let rows, cols: Int
    public let headerRow: Bool
    public let cells: [[TableCellData]] // [row][col]
}

public struct VariableData {
    public let type: TagType
    public let id: Int
    public let value: [UInt8]
}

public enum ContentData {
    case tlv([Tlv])
    case compressedTlv(CompressionType, [Tlv])
    case variables([VariableData])
}

public struct ScData {
    public let header: ScHeader
    public let contents: [ContentData]
    public let signatureAlgorithm: SignatureAlgorithm
    public let signatureKeyVersion: Int
    public let signature: [UInt8]?
    public let signedData: [UInt8]
}

// MARK: - Reader

private struct Reader {
    let d: [UInt8]; var p = 0
    init(_ d: [UInt8]) { self.d = d }
    mutating func byte() throws -> Int { try need(1); defer { p += 1 }; return Int(d[p]) }
    mutating func u16() throws -> Int { try need(2); defer { p += 2 }; return (Int(d[p]) << 8) | Int(d[p+1]) }
    mutating func u40() throws -> UInt64 {
        try need(5); var v: UInt64 = 0; for i in 0..<5 { v = (v << 8) | UInt64(d[p+i]) }; p += 5; return v
    }
    mutating func bytes(_ n: Int) throws -> [UInt8] { try need(n); defer { p += n }; return Array(d[p..<p+n]) }
    var remaining: Int { d.count - p }
    var hasMore: Bool { p < d.count }
    func need(_ n: Int) throws { if p + n > d.count { throw ScDataError.truncated(need: n, at: p, have: d.count - p) } }
}

// MARK: - Parser

public enum ScDataParser {

    public typealias Decryptor = (_ header: ScHeader, _ ivAndCiphertext: [UInt8]) throws -> [UInt8]
    public typealias Verifier = (_ header: ScHeader, _ alg: SignatureAlgorithm, _ keyVersion: Int,
                                 _ signedData: [UInt8], _ signature: [UInt8]) -> Bool
    public typealias DictionaryProvider = (_ header: ScHeader, _ version: Int) -> [UInt8]?

    public static func parse(_ payload: [UInt8],
                             decrypt: Decryptor? = nil,
                             verify: Verifier? = nil,
                             dictionary: DictionaryProvider? = nil) throws -> ScData {
        var r = Reader(payload)
        let magic = try r.byte()
        if magic != 0x03 { throw ScDataError.badMagic(magic) }
        let version = try r.byte()
        if version > 1 { throw ScDataError.unsupportedVersion(version) }
        let appId = try r.u16()
        let uniqueId = try r.u40()
        let minClient = try r.u16()
        let encByte = try r.byte()
        let encrypted = (encByte & 0x01) != 0

        let header: ScHeader
        let plain: [UInt8]
        if encrypted {
            let encType = (encByte >> 1) & 0x07
            let keyVer = try r.u16()
            let encLen = (encByte & 0x10) != 0 ? try r.u16() : try r.byte()
            let encData = try r.bytes(encLen)
            header = ScHeader(version: version, applicationId: appId, uniqueId: uniqueId,
                              minClientVersion: minClient, encrypted: true,
                              encryptionTypeCode: encType, encryptionKeyVersion: keyVer)
            guard let decrypt = decrypt else { throw ScDataError.unknown("Payload encrypted but no decryptor provided") }
            plain = try decrypt(header, encData)
        } else {
            header = ScHeader(version: version, applicationId: appId, uniqueId: uniqueId,
                              minClientVersion: minClient, encrypted: false,
                              encryptionTypeCode: 0, encryptionKeyVersion: 0)
            plain = try r.bytes(r.remaining)
        }

        var pr = Reader(plain)
        let contentCount = try pr.byte()
        var contents = [ContentData]()
        for _ in 0..<contentCount { contents.append(try readContent(&pr, header, dictionary)) }
        let contentEnd = pr.p

        var signed = Array(payload[0..<11]); signed.append(contentsOf: plain[0..<contentEnd])

        let sigByte = try pr.byte()
        let alg = try SignatureAlgorithm.from(sigByte & 0x07)
        let sigKeyVersion = (sigByte >> 3) & 0x0F
        var signature: [UInt8]? = nil
        if alg == .none {
            let crc = try pr.u16()
            if crc != crc16(signed) { throw ScDataError.unknown("CRC16 mismatch - payload corrupted") }
        } else {
            let sigLen = (sigByte & 0x80) != 0 ? try pr.u16() : try pr.byte()
            let sig = try pr.bytes(sigLen)
            signature = sig
            if let verify = verify, !verify(header, alg, sigKeyVersion, signed, sig) {
                throw ScDataError.unknown("Digital signature verification failed")
            }
        }
        return ScData(header: header, contents: contents, signatureAlgorithm: alg,
                      signatureKeyVersion: sigKeyVersion, signature: signature, signedData: signed)
    }

    // MARK: content

    private static func readContent(_ r: inout Reader, _ header: ScHeader,
                                    _ dict: DictionaryProvider?) throws -> ContentData {
        let b = try r.byte()
        let type = try ContentType.from(b & 0x0F)
        let len = (b & 0x10) != 0 ? try r.u16() : try r.byte()
        let data = try r.bytes(len)
        switch type {
        case .tlv:
            return .tlv(try readTlvList(data))
        case .compressedTlv:
            var d = Reader(data)
            let compCode = try d.byte() & 0x0F
            guard let comp = CompressionType(rawValue: compCode) else { throw ScDataError.unknown("Unknown compression \(compCode)") }
            var raw: [UInt8]
            if comp == .zipDict {
                let ver = try d.byte()
                guard let dp = dict?(header, ver) else { throw ScDataError.unknown("ZIP_DICT needs a dictionary provider") }
                raw = try inflateWithDictionary(try d.bytes(d.remaining), dictionary: dp)
            } else {
                raw = try decompress(try d.bytes(d.remaining), comp)
            }
            return .compressedTlv(comp, try readTlvList(raw))
        case .variables:
            return .variables(try readVariables(data))
        }
    }

    private static func readTlvList(_ data: [UInt8]) throws -> [Tlv] {
        var r = Reader(data); var out = [Tlv]()
        while r.hasMore { out.append(try readTlv(&r)) }
        return out
    }

    private static func readTlv(_ r: inout Reader) throws -> Tlv {
        let tag = try r.byte()
        let type = try TagType.from(tag & 0x0F)
        let len = (tag & 0x10) != 0 ? try r.u16() : try r.byte()
        var tagId: Int? = nil
        if (tag & 0x20) != 0 { tagId = (tag & 0x40) != 0 ? try r.u16() : try r.byte() }
        let value = try r.bytes(len)
        if type == .table {
            return Tlv(type: .table, tagId: tagId, value: [], table: try readTable(value))
        }
        return Tlv(type: type, tagId: tagId, value: value, table: nil)
    }

    private static func readTable(_ data: [UInt8]) throws -> TableData {
        var r = Reader(data)
        let dims = try r.byte()
        let rows = ((dims >> 4) & 0x0F) + 1
        let cols = (dims & 0x0F) + 1
        let flags = try r.byte()
        var grid = [[TableCellData]]()
        for _ in 0..<rows {
            var row = [TableCellData]()
            for _ in 0..<cols {
                let cb = try r.byte()
                if (cb & 0x20) == 0 { row.append(TableCellData(type: .string, value: [], present: false)); continue }
                let ct = try TagType.from(cb & 0x0F)
                let len = (cb & 0x10) != 0 ? try r.u16() : try r.byte()
                row.append(TableCellData(type: ct, value: try r.bytes(len), present: true))
            }
            grid.append(row)
        }
        return TableData(rows: rows, cols: cols, headerRow: (flags & 0x01) != 0, cells: grid)
    }

    private static func readVariables(_ data: [UInt8]) throws -> [VariableData] {
        var r = Reader(data); var out = [VariableData]()
        while r.hasMore {
            let b = try r.byte()
            let type = try TagType.from(b & 0x0F)
            let id = try r.byte() & 0x1F
            let len = (b & 0x10) != 0 ? try r.u16() : try r.byte()
            out.append(VariableData(type: type, id: id, value: try r.bytes(len)))
        }
        return out
    }
}

// MARK: - CRC16/CCITT-FALSE

func crc16(_ data: [UInt8]) -> Int {
    var crc = 0xFFFF
    for b in data {
        crc ^= Int(b) << 8
        for _ in 0..<8 {
            crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1
            crc &= 0xFFFF
        }
    }
    return crc
}

// MARK: - Decompression

func decompress(_ input: [UInt8], _ type: CompressionType) throws -> [UInt8] {
    switch type {
    case .zip:  return try rawInflate(input)                       // raw DEFLATE
    case .gzip: return try rawInflate(Array(input[10..<(input.count - 8)])) // strip gzip header/trailer
    case .zipDict: throw ScDataError.unknown("Use inflateWithDictionary for ZIP_DICT")
    }
}

/// Raw DEFLATE inflate via Apple's Compression framework (COMPRESSION_ZLIB is
/// raw DEFLATE, RFC 1951 - matching Java's Deflater(nowrap=true)).
func rawInflate(_ input: [UInt8]) throws -> [UInt8] {
    var stream = compression_stream(dst_ptr: UnsafeMutablePointer<UInt8>(bitPattern: 1)!, dst_size: 0,
                                    src_ptr: UnsafeMutablePointer<UInt8>(bitPattern: 1)!, src_size: 0, state: nil)
    guard compression_stream_init(&stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
        throw ScDataError.unknown("compression_stream_init failed")
    }
    defer { compression_stream_destroy(&stream) }

    var out = [UInt8]()
    let bufSize = 4096
    var dst = [UInt8](repeating: 0, count: bufSize)
    return try input.withUnsafeBufferPointer { src -> [UInt8] in
        stream.src_ptr = src.baseAddress!
        stream.src_size = src.count
        while true {
            let status = dst.withUnsafeMutableBufferPointer { d -> compression_status in
                stream.dst_ptr = d.baseAddress!
                stream.dst_size = bufSize
                return compression_stream_process(&stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
            }
            let produced = bufSize - stream.dst_size
            if produced > 0 { out.append(contentsOf: dst[0..<produced]) }
            if status == COMPRESSION_STATUS_END { return out }
            if status != COMPRESSION_STATUS_OK { throw ScDataError.unknown("inflate failed") }
        }
    }
}

/// ZIP_DICT: raw DEFLATE with a preset dictionary, via zlib (libz, on iOS).
/// Matches Java's Inflater(nowrap=true).setDictionary(...). For raw streams the
/// dictionary is set immediately after init (there is no Z_NEED_DICT signal).
func inflateWithDictionary(_ input: [UInt8], dictionary: [UInt8]) throws -> [UInt8] {
    var strm = z_stream()
    // windowBits = -15 → raw DEFLATE (no zlib/gzip header)
    guard inflateInit2_(&strm, -15, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
        throw ScDataError.unknown("inflateInit2 failed")
    }
    defer { inflateEnd(&strm) }

    var dict = dictionary
    let dictRc = dict.withUnsafeMutableBufferPointer { d in
        inflateSetDictionary(&strm, d.baseAddress, uInt(d.count))
    }
    guard dictRc == Z_OK else { throw ScDataError.unknown("inflateSetDictionary failed (\(dictRc))") }

    var src = input
    var out = [UInt8]()
    let bufSize = 4096
    var buf = [UInt8](repeating: 0, count: bufSize)

    let status: Int32 = src.withUnsafeMutableBufferPointer { s -> Int32 in
        strm.next_in = s.baseAddress
        strm.avail_in = uInt(s.count)
        while true {
            let rc: Int32 = buf.withUnsafeMutableBufferPointer { b -> Int32 in
                strm.next_out = b.baseAddress
                strm.avail_out = uInt(bufSize)
                return inflate(&strm, Z_FINISH)
            }
            let produced = bufSize - Int(strm.avail_out)
            if produced > 0 { out.append(contentsOf: buf[0..<produced]) }
            if rc == Z_STREAM_END { return Z_STREAM_END }
            if rc == Z_OK { continue }                               // needs more output space
            if rc == Z_BUF_ERROR && strm.avail_out == 0 { continue } // output buffer was full
            return rc                                                // truncated input or data error
        }
    }
    if status != Z_STREAM_END { throw ScDataError.unknown("ZIP_DICT inflate failed (\(status))") }
    return out
}
