#if canImport(Testing)
import Testing
import Kaudio

@Suite("Kaudio Swift Export Tests")
struct KaudioExportTests {
    @Test("Kaudio swift module imported cleanly")
    func testSwiftModuleLoads() throws {
        #expect(Bool(true), "Kaudio swift module imported cleanly")
    }
}
#elseif canImport(XCTest)
import XCTest
import Kaudio

final class KaudioExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        XCTAssertTrue(true, "Kaudio swift module imported cleanly")
    }
}
#endif
