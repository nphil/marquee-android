import Foundation

// Entry points for the JNI glue in CMarqueeJNI. Every buffer handed over is malloc'd by the glue and
// owned from here on, so Data takes it without another copy.

@_cdecl("marquee_swift_call")
public func marqueeSwiftCall(_ id: Int64, _ method: UnsafePointer<CChar>, _ payload: UnsafeMutablePointer<UInt8>?, _ length: Int) {
    Calls.shared.start(id: id, method: String(cString: method), payload: adopt(payload, length))
}

@_cdecl("marquee_swift_cancel")
public func marqueeSwiftCancel(_ id: Int64) {
    Calls.shared.cancel(id: id)
}

@_cdecl("marquee_swift_transport_response")
public func marqueeSwiftTransportResponse(
    _ id: Int64,
    _ status: Int32,
    _ headers: UnsafeMutablePointer<UInt8>?,
    _ headersLength: Int,
    _ body: UnsafeMutablePointer<UInt8>?,
    _ bodyLength: Int
) {
    OkHttpTransport.shared.complete(id: id, status: Int(status), headers: adopt(headers, headersLength), body: adopt(body, bodyLength))
}

@_cdecl("marquee_swift_transport_failure")
public func marqueeSwiftTransportFailure(_ id: Int64, _ failure: Int32) {
    OkHttpTransport.shared.fail(id: id, code: failure)
}

private func adopt(_ bytes: UnsafeMutablePointer<UInt8>?, _ length: Int) -> Data {
    guard let bytes else { return Data() }
    return Data(bytesNoCopy: bytes, count: length, deallocator: .free)
}
