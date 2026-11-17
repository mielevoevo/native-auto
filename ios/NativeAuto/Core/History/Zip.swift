import Foundation

/// A small ZIP reader and writer, Foundation only (raw DEFLATE through `NSData.compressed(using: .zlib)`, which is
/// RFC 1951 without a zlib header, exactly what ZIP's method 8 holds). Enough for the backup: Android's
/// `ZipOutputStream` files read here, and files written here open on Android and on any computer.
enum Zip {
    struct Entry: Equatable {
        var name: String
        var data: Data
    }

    enum ZipError: Error, Equatable {
        case notAZip
        case unsupported(String)
        case corrupt(String)
    }

    // MARK: - Writing

    static func write(_ entries: [Entry]) -> Data {
        var out = Data()
        var central = Data()
        let (dosTime, dosDate) = dosStamp(Date())
        for e in entries {
            let name = Data(e.name.utf8)
            let crc = crc32(e.data)
            // Deflate when it saves space (JSON and GPX do, a lot).
            let deflated = (try? (e.data as NSData).compressed(using: .zlib)) as Data?
            let (method, body): (UInt16, Data) = deflated.map { $0.count < e.data.count ? (8, $0) : (0, e.data) } ?? (0, e.data)
            let offset = UInt32(out.count)
            var local = Data()
            local.le32(0x0403_4b50)
            local.le16(20)               // version needed
            local.le16(0x0800)           // UTF-8 names
            local.le16(method)
            local.le16(dosTime); local.le16(dosDate)
            local.le32(crc)
            local.le32(UInt32(body.count)); local.le32(UInt32(e.data.count))
            local.le16(UInt16(name.count)); local.le16(0)
            out.append(local); out.append(name); out.append(body)

            central.le32(0x0201_4b50)
            central.le16(20); central.le16(20)
            central.le16(0x0800)
            central.le16(method)
            central.le16(dosTime); central.le16(dosDate)
            central.le32(crc)
            central.le32(UInt32(body.count)); central.le32(UInt32(e.data.count))
            central.le16(UInt16(name.count)); central.le16(0); central.le16(0)
            central.le16(0); central.le16(0); central.le32(0)
            central.le32(offset)
            central.append(name)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        var end = Data()
        end.le32(0x0605_4b50)
        end.le16(0); end.le16(0)
        end.le16(UInt16(entries.count)); end.le16(UInt16(entries.count))
        end.le32(UInt32(central.count)); end.le32(centralOffset)
        end.le16(0)
        out.append(end)
        return out
    }

    // MARK: - Reading

    /// Every entry, from the central directory (Android writes sizes after the data, so local headers alone don't
    /// say how long an entry is).
    static func read(_ zip: Data) throws -> [Entry] {
        let b = [UInt8](zip)
        guard b.count >= 22 else { throw ZipError.notAZip }
        var eocd = -1
        var i = b.count - 22
        let floor = max(0, b.count - 22 - 65_535)
        while i >= floor {
            if b.u32(i) == 0x0605_4b50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw ZipError.notAZip }
        let count = Int(b.u16(eocd + 10))
        var p = Int(b.u32(eocd + 16))
        var entries: [Entry] = []
        for _ in 0..<count {
            guard p + 46 <= b.count, b.u32(p) == 0x0201_4b50 else { throw ZipError.corrupt("central directory") }
            let method = b.u16(p + 10)
            let crc = b.u32(p + 16)
            let compSize = Int(b.u32(p + 20)), size = Int(b.u32(p + 24))
            let nameLen = Int(b.u16(p + 28)), extraLen = Int(b.u16(p + 30)), commentLen = Int(b.u16(p + 32))
            let localOffset = Int(b.u32(p + 42))
            guard p + 46 + nameLen <= b.count else { throw ZipError.corrupt("name") }
            let name = String(decoding: b[(p + 46)..<(p + 46 + nameLen)], as: UTF8.self)
            p += 46 + nameLen + extraLen + commentLen

            guard localOffset + 30 <= b.count, b.u32(localOffset) == 0x0403_4b50 else { throw ZipError.corrupt("local header") }
            let start = localOffset + 30 + Int(b.u16(localOffset + 26)) + Int(b.u16(localOffset + 28))
            guard start + compSize <= b.count else { throw ZipError.corrupt("entry \(name)") }
            let raw = Data(b[start..<(start + compSize)])
            let data: Data
            switch method {
            case 0: data = raw
            case 8:
                if size == 0 { data = Data() } else {
                    guard let d = try? (raw as NSData).decompressed(using: .zlib) as Data else { throw ZipError.corrupt("entry \(name)") }
                    data = d
                }
            default: throw ZipError.unsupported("compression \(method)")
            }
            guard data.count == size, crc32(data) == crc else { throw ZipError.corrupt("entry \(name)") }
            entries.append(Entry(name: name, data: data))
        }
        return entries
    }

    // MARK: - Helpers

    private static let crcTable: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { (buf: UnsafeRawBufferPointer) in
            for byte in buf { c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        }
        return c ^ 0xFFFF_FFFF
    }

    private static func dosStamp(_ date: Date) -> (UInt16, UInt16) {
        let c = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let time = UInt16((c.hour! << 11) | (c.minute! << 5) | (c.second! / 2))
        let day = UInt16(((max(1980, c.year!) - 1980) << 9) | (c.month! << 5) | c.day!)
        return (time, day)
    }
}

private extension Data {
    mutating func le16(_ v: UInt16) { append(UInt8(v & 0xFF)); append(UInt8(v >> 8)) }
    mutating func le32(_ v: UInt32) { le16(UInt16(v & 0xFFFF)); le16(UInt16(v >> 16)) }
}

private extension Array where Element == UInt8 {
    func u16(_ i: Int) -> UInt16 { UInt16(self[i]) | UInt16(self[i + 1]) << 8 }
    func u32(_ i: Int) -> UInt32 { UInt32(u16(i)) | UInt32(u16(i + 2)) << 16 }
}
