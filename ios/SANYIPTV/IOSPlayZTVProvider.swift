import Foundation
import CommonCrypto

struct IOSPlayZTVCategory {
    let name: String
    let api: String
    let type: String
    let logo: String?
}

private struct IOSPlayZTVCategoryWrapper: Decodable {
    let cat: String
}

private struct IOSPlayZTVCategoryData: Decodable {
    let visible: Bool?
    let name: String
    let logo: String?
    let type: String?
    let api: String
}

final class IOSPlayZTVProvider {
    static let shared = IOSPlayZTVProvider()
    private let session: URLSession
    private let defaults = ["https://tourniquest.site", "https://adsflw.xyz", "https://playztv2828.store"]
    private var cachedBaseURL: URL?

    private init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 18
        config.timeoutIntervalForResource = 28
        config.httpAdditionalHeaders = [
            "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 10; SM-A505F)",
            "Accept": "*/*",
            "Cache-Control": "no-cache, no-store"
        ]
        session = URLSession(configuration: config)
    }

    func loadCategories(completion: @escaping (Result<[IOSPlayZTVCategory], Error>) -> Void) {
        resolveBaseURL { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error): completion(.failure(error))
            case .success(let base):
                self.fetchText(URL(string: "categories.txt", relativeTo: base)!) { response in
                    switch response {
                    case .failure(let error): completion(.failure(error))
                    case .success(let encrypted):
                        guard let decoded = Self.decrypt(encrypted),
                              let data = decoded.data(using: .utf8) else {
                            completion(.failure(ProviderError.invalidPayload)); return
                        }
                        do {
                            let wrappers = try JSONDecoder().decode([IOSPlayZTVCategoryWrapper].self, from: data)
                            var categories = wrappers.compactMap { wrapper -> IOSPlayZTVCategory? in
                                guard let categoryData = wrapper.cat.data(using: .utf8),
                                      let category = try? JSONDecoder().decode(IOSPlayZTVCategoryData.self, from: categoryData),
                                      category.visible != false else { return nil }
                                let name = category.name.trimmingCharacters(in: .whitespacesAndNewlines)
                                let api = category.api.trimmingCharacters(in: .whitespacesAndNewlines)
                                guard !name.isEmpty, !api.isEmpty else { return nil }
                                return IOSPlayZTVCategory(
                                    name: name,
                                    api: api,
                                    type: category.type?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "custom",
                                    logo: category.logo
                                )
                            }
                            // Android PlayZTVProviderManager.fetchLiveEvents() reads this fixed endpoint.
                            if !categories.contains(where: { $0.api == "events.txt" }) {
                                categories.append(IOSPlayZTVCategory(name: "Live Events", api: "events.txt", type: "events", logo: nil))
                            }
                            var seen = Set<String>()
                            categories = categories.filter { seen.insert($0.api).inserted }
                            completion(.success(categories))
                        } catch { completion(.failure(error)) }
                    }
                }
            }
        }
    }

    // Each categories.txt cat entry contains JSON metadata, including its provider name and API path.
    // Callers use that exact API path rather than constructing guessed endpoint names.
    func loadCategoryPayload(path: String, completion: @escaping (Result<String, Error>) -> Void) {
        resolveBaseURL { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error): completion(.failure(error))
            case .success(let base):
                let target = URL(string: path, relativeTo: base) ?? base.appendingPathComponent(path)
                self.fetchText(target) { result in
                    completion(result.map { Self.decrypt($0) ?? $0 })
                }
            }
        }
    }

    private func resolveBaseURL(completion: @escaping (Result<URL, Error>) -> Void) {
        if let cachedBaseURL { completion(.success(cachedBaseURL)); return }
        fetchRemoteConfig { [weak self] remote in
            guard let self else { return }
            if let value = remote, let url = URL(string: value), url.scheme != nil {
                self.cachedBaseURL = url
                completion(.success(url))
                return
            }
            self.probeFallback(index: 0, completion: completion)
        }
    }

    private func fetchRemoteConfig(completion: @escaping (String?) -> Void) {
        guard let url = URL(string: "https://firebaseremoteconfig.googleapis.com/v1/projects/516859456626/namespaces/firebase:fetch") else {
            completion(nil); return
        }
        let instance = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let payload: [String: Any] = [
            "appInstanceId": instance, "appInstanceIdToken": "",
            "appId": "1:516859456626:android:12a75869902c4f8a6826eb",
            "countryCode": "US", "languageCode": "en-US", "platformVersion": "30",
            "timeZone": "UTC", "appVersion": "2.1", "appBuild": "4",
            "packageName": "com.playz.tv", "sdkVersion": "22.1.0",
            "analyticsUserProperties": [String: String]()
        ]
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("yes", forHTTPHeaderField: "X-Google-GFE-Can-Retry")
        request.setValue("okhttp/4.12.0", forHTTPHeaderField: "User-Agent")
        request.setValue("com.playz.tv", forHTTPHeaderField: "X-Android-Package")
        request.setValue("AIzaSyDKRqLlbaZBIpHzLBiQTUrJqr3gN-nDWWc", forHTTPHeaderField: "X-Goog-Api-Key")
        session.dataTask(with: request) { data, _, _ in
            let value: [String: Any]? = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            let entries = value?["entries"] as? [String: Any]
            completion(entries?["api_url"] as? String)
        }.resume()
    }

    private func probeFallback(index: Int, completion: @escaping (Result<URL, Error>) -> Void) {
        guard index < defaults.count, let url = URL(string: defaults[index]) else {
            guard let first = URL(string: defaults[0]) else {
                completion(.failure(ProviderError.serverUnavailable)); return
            }
            cachedBaseURL = first
            completion(.success(first))
            return
        }
        var request = URLRequest(url: url.appendingPathComponent("categories.txt"))
        request.httpMethod = "HEAD"
        session.dataTask(with: request) { [weak self] _, response, error in
            if error == nil, let status = (response as? HTTPURLResponse)?.statusCode, status < 500 {
                self?.cachedBaseURL = url
                completion(.success(url))
            } else { self?.probeFallback(index: index + 1, completion: completion) }
        }.resume()
    }

    private func fetchText(_ url: URL, completion: @escaping (Result<String, Error>) -> Void) {
        session.dataTask(with: url) { data, response, error in
            if let error { completion(.failure(error)); return }
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let data, let text = String(data: data, encoding: .utf8) else {
                completion(.failure(ProviderError.badResponse)); return
            }
            completion(.success(text.trimmingCharacters(in: .whitespacesAndNewlines)))
        }.resume()
    }

    private enum ProviderError: Error {
        case serverUnavailable, badResponse, invalidPayload
    }

    private static func decrypt(_ input: String) -> String? {
        let nativeKey = Array("cz14RStkN01PVE5w".utf8)
        let nativeIV = Array("WTlEvckd2UR41sdk".utf8)
        let primaryKey = Array("b/1jml5nk4x5k7pN".utf8)
        let primaryIV = Array("14nMk8mN5Kl5KL7l".utf8)
        let fallbackKey = Array("m5Kl5nk4xK1kN7pN".utf8)
        let fallbackIV = Array("k5K4nM8mKlNL7l15".utf8)
        let from = Array("aAbBcCdDeEfFgGhHiIjJkKlLmNoOpPqQrRsStTuUvVwWxXyYzZ")
        let to = Array("fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS")
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{") || trimmed.hasPrefix("[") { return trimmed }
        if let first = Data(base64Encoded: trimmed, options: .ignoreUnknownCharacters) {
            let reversed = Array(first.reversed())
            var swapped = reversed
            if swapped.count > 1 {
                for i in stride(from: 0, through: swapped.count - 2, by: 2) { swapped.swapAt(i, i + 1) }
            }
            if let secondText = String(bytes: swapped, encoding: .ascii),
               let second = Data(base64Encoded: secondText, options: .ignoreUnknownCharacters),
               let plain = aes(second, key: nativeKey, iv: nativeIV),
               let text = String(data: plain, encoding: .utf8),
               (text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") || text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("[")) {
                return text.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        let reverseMap = Dictionary(uniqueKeysWithValues: zip(to, from))
        let restored = String(trimmed.filter { !$0.isWhitespace }.map { reverseMap[$0] ?? $0 })
        if let first = Data(base64Encoded: pad(restored), options: .ignoreUnknownCharacters),
           let firstText = String(data: first, encoding: .ascii),
           let cipher = Data(base64Encoded: pad(firstText), options: .ignoreUnknownCharacters),
           let plain = aes(cipher, key: primaryKey, iv: primaryIV),
           let text = String(data: plain, encoding: .utf8),
           text.trimmingCharacters(in: .whitespacesAndNewlines).first.map({ $0 == "{" || $0 == "[" }) == true {
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let cipher = Data(base64Encoded: pad(trimmed), options: .ignoreUnknownCharacters),
           let plain = aes(cipher, key: fallbackKey, iv: fallbackIV),
           let text = String(data: plain, encoding: .utf8),
           text.trimmingCharacters(in: .whitespacesAndNewlines).first.map({ $0 == "{" || $0 == "[" }) == true {
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return nil
    }

    private static func pad(_ value: String) -> String {
        var result = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while result.count % 4 != 0 { result.append("=") }
        return result
    }

    private static func aes(_ data: Data, key: [UInt8], iv: [UInt8]) -> Data? {
        guard data.count % kCCBlockSizeAES128 == 0 else { return nil }
        var output = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let status = CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                             key, key.count, iv, [UInt8](data), data.count, &output, output.count, &moved)
        guard status == kCCSuccess else { return nil }
        return Data(output.prefix(moved))
    }
}
