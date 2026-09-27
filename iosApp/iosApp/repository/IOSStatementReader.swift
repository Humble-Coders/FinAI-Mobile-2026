import Foundation
import PDFKit
import SharedLogic
import UIKit
import Vision

/// Reads a statement on the device, and nowhere else.
///
/// Three paths, in the order worth trying:
///
/// 1. **The PDF's own text layer** — what a statement downloaded from a bank
///    contains. The characters are in the file with their positions; no model,
///    no guessing.
/// 2. **A PDF with no text layer** — a scan. Render each page, read the pixels.
/// 3. **An image** — a photo or screenshot. Read the pixels directly.
///
/// Deliberately *not* conforming to the shared `StatementReader` protocol.
/// Implementing a Kotlin `suspend` interface from Swift means writing against
/// SKIE's generated completion-handler shape, and nothing shared ever calls
/// into here — the flow is iOS → reader → shared redactor → shared repository.
/// Conformance would buy symmetry and cost a bridge that compiles while
/// behaving oddly. Android implements it because there it is free.
final class IOSStatementReader {

    enum ReadError: Error {
        /// Encrypted, and either no password was given or the one given is wrong.
        case passwordRequired(wrongPassword: Bool)
        /// A format we cannot open at all.
        case unsupported(String)
        /// Opened, but nothing that reads as text came out.
        case nothingReadable
        /// More pages than the API accepts, refused before any page is read.
        case tooManyPages(pages: Int, limit: Int)
    }

    /// Below this, a "text layer" is page furniture — a header, a page number —
    /// and the document is really a scan.
    private let meaningfulCharacters = 40

    /// - Parameter password: used here and never sent anywhere.
    /// - Parameter onPage: progress; a twenty-page scan is a long wait and a
    ///   bare spinner reads as a hang.
    func read(
        url: URL,
        password: String? = nil,
        onPage: @escaping (Int, Int) -> Void = { _, _ in }
    ) async throws -> ExtractedDocument {
        if isImage(url) {
            return try await readImage(url: url, onPage: onPage)
        }
        guard let document = PDFDocument(url: url) else {
            throw ReadError.unsupported("not a pdf")
        }
        if document.isLocked {
            guard let password, document.unlock(withPassword: password) else {
                throw ReadError.passwordRequired(wrongPassword: password != nil)
            }
        }
        // Before a single page is read. Counting after the read — which is
        // where the send-time check sits — costs the user the whole OCR pass
        // to be told the file was never acceptable.
        let limit = Int(StatementLimits.shared.MAX_PAGES)
        if document.pageCount > limit {
            throw ReadError.tooManyPages(pages: document.pageCount, limit: limit)
        }
        if let withText = readTextLayer(document, onPage: onPage) {
            return withText
        }
        return try await readScanned(document, onPage: onPage)
    }

    private func isImage(_ url: URL) -> Bool {
        ["png", "jpg", "jpeg", "heic", "heif"].contains(url.pathExtension.lowercased())
    }

    // MARK: - Path 1: the text layer

    private func readTextLayer(
        _ document: PDFDocument,
        onPage: (Int, Int) -> Void
    ) -> ExtractedDocument? {
        var pages: [ExtractedPage] = []
        var characters = 0

        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            let lines = self.lines(on: page)
            characters += lines.reduce(0) { $0 + $1.text.count }
            pages.append(ExtractedPage(index: Int32(index), lines: lines))
        }

        // No per-page progress here, matching Android. This pass does not yet
        // know whether it is the answer, and when it is not, the OCR pass
        // counts from one again — a bar that fills, resets and then crawls
        // reads as a fault. Reading a text layer is the fast path, so it
        // reports once, on success.
        guard characters >= meaningfulCharacters else { return nil }
        onPage(document.pageCount, document.pageCount)
        return ExtractedDocument(pages: pages, source: .pdfText)
    }

    /// Splits a page's text into lines, keeping where each one sat.
    ///
    /// `page.string` gives the text but no geometry, and a statement is a table
    /// — reading order alone loses which column a number was in. The character
    /// bounds are walked to rebuild each line's box.
    private func lines(on page: PDFPage) -> [ExtractedLine] {
        guard let text = page.string, !text.isEmpty else { return [] }

        // `characterBounds` answers in PDF user space, whose origin is the
        // BOTTOM left; `BoundingBox` is documented top-left, and Android's
        // PDFBox path already reports top-left. Flip against the page height
        // here so a box means the same thing on both platforms.
        let pageHeight = page.bounds(for: .mediaBox).height

        var result: [ExtractedLine] = []
        var characterIndex = 0

        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            // UTF-16 units, not Characters: `characterBounds(at:)` and
            // `numberOfCharacters` index UTF-16, and the two agree only for
            // ASCII. A decomposed accent — `E` + combining acute, which PDFs
            // carry routinely and French statements are full of — is one
            // Character and two UTF-16 units, so counting Characters drifts
            // the index and every later line on the page gets the wrong box.
            let length = raw.utf16.count
            defer { characterIndex += length + 1 }
            guard !line.isEmpty, characterIndex < page.numberOfCharacters else { continue }

            var box = CGRect.null
            let end = min(characterIndex + length, page.numberOfCharacters)
            for position in characterIndex..<end {
                box = box.union(page.characterBounds(at: position))
            }
            result.append(
                ExtractedLine(
                    text: line,
                    box: BoundingBox(
                        left: Float(box.minX),
                        top: Float(pageHeight - box.maxY),
                        right: Float(box.maxX),
                        bottom: Float(pageHeight - box.minY)
                    )
                )
            )
        }
        return result
    }

    // MARK: - Path 2: a scanned PDF

    private func readScanned(
        _ document: PDFDocument,
        onPage: @escaping (Int, Int) -> Void
    ) async throws -> ExtractedDocument {
        var pages: [ExtractedPage] = []

        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            // Twice nominal size: OCR on a 72-dpi render of small print reads
            // plausible nonsense rather than failing, and a wrong amount that
            // looks right is the worst output this feature can produce.
            let bounds = page.bounds(for: .mediaBox)
            let scaled = CGSize(width: bounds.width * 2, height: bounds.height * 2)
            let image = page.thumbnail(of: scaled, for: .mediaBox)
            guard let cgImage = image.cgImage else { continue }

            pages.append(
                ExtractedPage(index: Int32(index), lines: try await recognise(cgImage))
            )
            onPage(index + 1, document.pageCount)
        }

        guard pages.contains(where: { !$0.lines.isEmpty }) else {
            throw ReadError.nothingReadable
        }
        return ExtractedDocument(pages: pages, source: .ocr)
    }

    // MARK: - Path 3: an image

    private func readImage(
        url: URL,
        onPage: @escaping (Int, Int) -> Void
    ) async throws -> ExtractedDocument {
        guard let image = UIImage(contentsOfFile: url.path), let cgImage = image.cgImage else {
            throw ReadError.unsupported("cannot open image")
        }
        let lines = try await recognise(cgImage)
        onPage(1, 1)
        guard !lines.isEmpty else { throw ReadError.nothingReadable }
        return ExtractedDocument(
            pages: [ExtractedPage(index: 0, lines: lines)],
            source: .ocr
        )
    }

    // MARK: - Vision

    private func recognise(_ image: CGImage) async throws -> [ExtractedLine] {
        try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let observations = request.results as? [VNRecognizedTextObservation] ?? []
                let height = CGFloat(image.height)
                let width = CGFloat(image.width)
                let lines = observations.compactMap { observation -> ExtractedLine? in
                    guard let candidate = observation.topCandidates(1).first else { return nil }
                    // Vision reports a normalised box with the origin at the
                    // bottom left; everything else here is top-left in pixels.
                    let box = observation.boundingBox
                    return ExtractedLine(
                        text: candidate.string,
                        box: BoundingBox(
                            left: Float(box.minX * width),
                            top: Float((1 - box.maxY) * height),
                            right: Float(box.maxX * width),
                            bottom: Float((1 - box.minY) * height)
                        )
                    )
                }
                continuation.resume(returning: lines)
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = false  // merchant codes are not words

            do {
                try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }
}
