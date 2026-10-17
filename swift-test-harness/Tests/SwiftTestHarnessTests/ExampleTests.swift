#if canImport(Testing)
import Testing
import Kaudio

@Suite("Kaudio Swift Export Tests")
struct KaudioExportTests {
    @Test("Kaudio swift module imported cleanly")
    func testSwiftModuleLoads() throws {
        let sample = FloatSample(value: 0.5)
        #expect(sample.toI16() == 16383)
        let outputData = AudioOutputData.Companion.shared.new(
            inputSampleRate: 24000,
            outputSampleRate: 24000
        )
        #expect(outputData.isEmpty())
    }
}
#elseif canImport(XCTest)
import XCTest
import Kaudio

final class KaudioExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        let sample = FloatSample(value: 0.5)
        XCTAssertEqual(sample.toI16(), 16383)
        let outputData = AudioOutputData.Companion.shared.new(
            inputSampleRate: 24000,
            outputSampleRate: 24000
        )
        XCTAssertTrue(outputData.isEmpty())
    }
}
#endif
