import UIKit
import AVKit

struct IOSMovieHit {
    let title: String
    let url: URL
    let kind: String
}

struct IOSMovieSource {
    let name: String
    let url: URL
    let playable: Bool
}

final class IOSMultiMoviesProvider {
    static let shared = IOSMultiMoviesProvider()
    private let base = URL(string: "https://multimovies.makeup")!
    private let session = URLSession(configuration: .default)
    private let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 Version/16.0 Mobile/15E148 Safari/604.1"
    private var searchCache: [String: (Date, [IOSMovieHit])] = [:]
    private var sourceCache: [String: (Date, [IOSMovieSource])] = [:]
    private let cacheTTL: TimeInterval = 300

    private init() {}

    private func request(_ url: URL, referer: URL? = nil, completion: @escaping (Data?, URLResponse?) -> Void) {
        var req = URLRequest(url: url)
        req.timeoutInterval = 12
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("text/html,application/json,*/*", forHTTPHeaderField: "Accept")
        req.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        if let referer = referer { req.setValue(referer.absoluteString, forHTTPHeaderField: "Referer") }
        session.dataTask(with: req) { data, response, _ in completion(data, response) }.resume()
    }

    private func html(_ url: URL, referer: URL? = nil, completion: @escaping (String?) -> Void) {
        request(url, referer: referer) { data, response in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let text = (200...299).contains(status) ? data.flatMap { String(data: $0, encoding: .utf8) } : nil
            completion(text)
        }
    }

    private func absolute(_ raw: String) -> URL? {
        let value = raw.replacingOccurrences(of: "\\/", with: "/").replacingOccurrences(of: "&amp;", with: "&").trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("//") { return URL(string: "https:" + value) }
        if let url = URL(string: value), let scheme = url.scheme, ["http", "https"].contains(scheme.lowercased()) { return url }
        return URL(string: value, relativeTo: base)?.absoluteURL
    }

    private func matchesKind(_ url: URL, series: Bool) -> Bool {
        let path = url.path.lowercased()
        let isSeries = path.contains("/series/") || path.contains("/tvshows/") || path.contains("/tv/")
        return series ? isSeries : !isSeries
    }

    func search(_ query: String, series: Bool, completion: @escaping ([IOSMovieHit]) -> Void) {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { completion([]); return }
        let key = (series ? "tv:" : "movie:") + term.lowercased()
        if let cached = searchCache[key], Date().timeIntervalSince(cached.0) < cacheTTL {
            completion(cached.1); return
        }
        var components = URLComponents(url: base.appendingPathComponent("api/search"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "q", value: term), URLQueryItem(name: "limit", value: "12")]
        guard let url = components.url else { completion([]); return }
        request(url) { [weak self] data, response in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let root = (200...299).contains(status) ? (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [String: Any] : nil
            let rows = root?["results"] as? [[String: Any]] ?? []
            let parsed = rows.compactMap { row -> IOSMovieHit? in
                let rawType = (row["type"] as? String ?? "").lowercased()
                let isTV = ["tv", "series", "show"].contains(rawType) || (rawType.isEmpty && row["name"] != nil)
                guard isTV == series else { return nil }
                let title = ((row["title"] as? String) ?? (row["name"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let rawURL = (row["url"] as? String) ?? (row["link"] as? String) ?? (row["permalink"] as? String) ?? ""
                guard !title.isEmpty, let hitURL = self?.absolute(rawURL), self?.matchesKind(hitURL, series: series) == true else { return nil }
                return IOSMovieHit(title: title, url: hitURL, kind: series ? "Series" : "Movie")
            }
            if !parsed.isEmpty {
                let result = Array(Dictionary(grouping: parsed, by: { $0.url.absoluteString }).compactMap { $0.value.first }.prefix(8))
                self?.searchCache[key] = (Date(), result)
                DispatchQueue.main.async { completion(result) }
            } else {
                self?.fallbackSearch(term, series: series) { hits in
                    self?.searchCache[key] = (Date(), hits)
                    completion(hits)
                }
            }
        }
    }

    private func fallbackSearch(_ term: String, series: Bool, completion: @escaping ([IOSMovieHit]) -> Void) {
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        components.path = "/search"
        components.queryItems = [URLQueryItem(name: "q", value: term)]
        guard let searchURL = components.url else { completion([]); return }
        html(base) { [weak self] home in
            guard let self = self, let home = home else { completion([]); return }
            let block = self.firstGroup(#"dtGonza\\s*=\\s*\\{[^}]*\\}"#, in: home) ?? ""
            let nonce = self.firstGroup(#"\"nonce\"\\s*:\\s*\"([A-Za-z0-9]+)\""#, in: block)
            guard let nonce = nonce else {
                self.parseSearchHTML(searchURL, series: series, completion: completion); return
            }
            var api = URLComponents(url: self.base, resolvingAgainstBaseURL: false)!
            api.path = "/wp-json/dooplay/search/"
            api.queryItems = [URLQueryItem(name: "keyword", value: term), URLQueryItem(name: "nonce", value: nonce)]
            guard let apiURL = api.url else { self.parseSearchHTML(searchURL, series: series, completion: completion); return }
            self.request(apiURL, referer: self.base) { data, response in
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                let root = (200...299).contains(status) ? (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [String: Any] : nil
                let hits = (root?.values.compactMap { $0 as? [String: Any] } ?? []).compactMap { row -> IOSMovieHit? in
                    let title = row["title"] as? String ?? ""
                    guard let raw = row["url"] as? String, let url = self.absolute(raw), url.host?.lowercased() == self.base.host?.lowercased(), self.matchesKind(url, series: series), !title.isEmpty else { return nil }
                    return IOSMovieHit(title: title, url: url, kind: series ? "Series" : "Movie")
                }
                if hits.isEmpty { self.parseSearchHTML(searchURL, series: series, completion: completion) }
                else { DispatchQueue.main.async { completion(Array(hits.prefix(8))) } }
            }
        }
    }

    private func parseSearchHTML(_ url: URL, series: Bool, completion: @escaping ([IOSMovieHit]) -> Void) {
        html(url) { [weak self] body in
            guard let self = self, let body = body else { completion([]); return }
            let articles = self.allGroups(#"<article\\b[^>]*>(.*?)</article>"#, in: body)
            var hits = [IOSMovieHit]()
            for article in articles {
                guard let rawLink = self.firstGroup(#"<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>"#, in: article),
                      let link = self.absolute(rawLink), link.host?.lowercased() == self.base.host?.lowercased(),
                      self.matchesKind(link, series: series) else { continue }
                let name = self.firstGroup(#"<img[^>]+alt=[\"']([^\"']+)[\"']"#, in: article)
                    ?? self.firstGroup(#"<h[1-3][^>]*>(.*?)</h[1-3]>"#, in: article)?.replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
                if let name = name, !name.isEmpty { hits.append(IOSMovieHit(title: name, url: link, kind: series ? "Series" : "Movie")) }
            }
            DispatchQueue.main.async { completion(Array(hits.prefix(8))) }
        }
    }

    func sources(for detailURL: URL, completion: @escaping ([IOSMovieSource]) -> Void) {
        guard detailURL.host?.lowercased() == base.host?.lowercased() else { completion([]); return }
        let key = detailURL.absoluteString
        if let cached = sourceCache[key], Date().timeIntervalSince(cached.0) < cacheTTL { completion(cached.1); return }
        html(detailURL) { [weak self] body in
            guard let self = self, let body = body else { DispatchQueue.main.async { completion([]) }; return }
            var candidates = [(String, URL)]()
            if let config = self.objectAfter(marker: "watchConfig", in: body),
               let data = config.data(using: .utf8),
               let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
               let servers = root["initialServers"] as? [[String: Any]] {
                for (index, server) in servers.enumerated() {
                    let name = server["name"] as? String ?? "Server \\(index + 1)"
                    if let raw = server["url"] as? String, let url = self.absolute(raw) { candidates.append((name, url)) }
                }
            }
            let chips = self.allGroups(#"<button[^>]*class=[\"'][^\"']*server-chip[^\"']*[\"'][^>]*>"#, in: body)
            for chip in chips {
                guard let raw = self.firstGroup(#"data-url=[\"']([^\"']+)[\"']"#, in: chip), let url = self.absolute(raw) else { continue }
                let name = self.firstGroup(#"data-id=[\"']([^\"']+)[\"']"#, in: chip) ?? "Server"
                candidates.append((name, url))
            }
            self.resolveCandidates(candidates, cacheKey: key, index: 0, results: [], completion: completion)
        }
    }

    private func resolveCandidates(_ candidates: [(String, URL)], cacheKey: String, index: Int, results: [IOSMovieSource], completion: @escaping ([IOSMovieSource]) -> Void) {
        guard index < candidates.count else {
            sourceCache[cacheKey] = (Date(), results)
            DispatchQueue.main.async { completion(results) }; return
        }
        let (name, url) = candidates[index]
        if isMedia(url) {
            resolveCandidates(candidates, cacheKey: cacheKey, index: index + 1, results: results + [IOSMovieSource(name: name, url: url, playable: true)], completion: completion)
            return
        }
        html(url, referer: base) { [weak self] body in
            guard let self = self else { completion(results); return }
            let patterns = [
                #"<source[^>]+src=[\"']([^\"']+)[\"']"#,
                #"[\\"']file[\\"']\\s*:\\s*[\\"']([^\\"']+)[\\"']"#,
                #"[\\"']src[\\"']\\s*:\\s*[\\"'](https?://[^\\"']+)[\\"']"#
            ]
            var mediaURL: URL?
            if let body = body {
                for pattern in patterns {
                    if let raw = self.firstGroup(pattern, in: body), let found = self.absolute(raw), self.isMedia(found) { mediaURL = found; break }
                }
            }
            let next = results + [IOSMovieSource(name: name, url: mediaURL ?? url, playable: mediaURL != nil)]
            self.resolveCandidates(candidates, cacheKey: cacheKey, index: index + 1, results: next, completion: completion)
        }
    }

    private func isMedia(_ url: URL) -> Bool {
        let value = url.absoluteString.lowercased()
        return value.range(of: #"(\\.m3u8|\\.mp4)(?:$|[?#])"#, options: .regularExpression) != nil
    }

    private func firstGroup(_ pattern: String, in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              match.numberOfRanges > 1, let range = Range(match.range(at: 1), in: text) else { return nil }
        return String(text[range]).replacingOccurrences(of: "&amp;", with: "&").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func allGroups(_ pattern: String, in text: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { match in
            guard match.numberOfRanges > 1, let range = Range(match.range(at: 1), in: text) else { return nil }
            return String(text[range])
        }
    }

    private func objectAfter(marker: String, in text: String) -> String? {
        guard let range = text.range(of: marker, options: .caseInsensitive),
              let start = text[range.upperBound...].firstIndex(of: "{") else { return nil }
        var depth = 0
        var quoted = false
        var escaped = false
        for index in text.indices[ start... ] {
            let ch = text[index]
            if quoted {
                if escaped { escaped = false }
                else if ch == "\\" { escaped = true }
                else if ch == "\"" { quoted = false }
            } else {
                if ch == "\"" { quoted = true }
                else if ch == "{" { depth += 1 }
                else if ch == "}" {
                    depth -= 1
                    if depth == 0 { return String(text[start...index]) }
                }
            }
        }
        return nil
    }
}

final class IOSMoviesHomeViewController: UITableViewController, UISearchBarDelegate {
    private let searchBar = UISearchBar()
    private let kindControl = UISegmentedControl(items: ["Movies", "Series"])
    private var query = ""
    private var isSeries = false
    private var hits = [IOSMovieHit]()
    private var searching = false

    init() {
        super.init(style: .grouped)
        title = "Movies"
        tabBarItem = UITabBarItem(title: "Movies", image: IOSIcon.image("film"), selectedImage: IOSIcon.image("film.fill"))
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = IOSAppStyle.background
        tableView.backgroundColor = IOSAppStyle.background
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "MovieHit")
        tableView.rowHeight = 64
        searchBar.placeholder = "Search MultiMovies"
        searchBar.delegate = self
        searchBar.searchBarStyle = .minimal
        searchBar.tintColor = IOSAppStyle.accent
        kindControl.selectedSegmentIndex = 0
        kindControl.addTarget(self, action: #selector(kindChanged), for: .valueChanged)
        let header = UIStackView(arrangedSubviews: [kindControl, searchBar])
        header.axis = .vertical
        header.spacing = 6
        header.layoutMargins = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        header.isLayoutMarginsRelativeArrangement = true
        header.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: 112)
        tableView.tableHeaderView = header
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Search", style: .plain, target: self, action: #selector(runSearch))
        updateEmpty()
    }

    @objc private func kindChanged() {
        isSeries = kindControl.selectedSegmentIndex == 1
        runSearch()
    }

    @objc private func runSearch() {
        query = (searchBar.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        searching = true
        tableView.reloadData()
        IOSMultiMoviesProvider.shared.search(query, series: isSeries) { [weak self] hits in
            guard let self = self, self.query == (self.searchBar.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                  self.isSeries == (self.kindControl.selectedSegmentIndex == 1) else { return }
            self.hits = hits
            self.searching = false
            self.tableView.reloadData()
            self.updateEmpty()
        }
    }

    func searchBarSearchButtonClicked(_ searchBar: UISearchBar) { searchBar.resignFirstResponder(); runSearch() }
    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) { query = searchText }

    private func updateEmpty() {
        let label = UILabel()
        label.textAlignment = .center
        label.numberOfLines = 0
        label.textColor = IOSAppStyle.secondaryText
        label.text = searching ? "Searching MultiMovies…" : (query.isEmpty ? "Search for a movie or series to find MultiMovies sources." : (hits.isEmpty ? "No results. Try another title or refresh your search." : nil))
        tableView.backgroundView = label.text == nil ? nil : label
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { hits.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "MovieHit", for: indexPath)
        let hit = hits[indexPath.row]
        cell.textLabel?.text = hit.title + "  •  " + hit.kind
        cell.textLabel?.textColor = IOSAppStyle.primaryText
        cell.textLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        cell.backgroundColor = IOSAppStyle.card
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let hit = hits[indexPath.row]
        let loading = UIAlertController(title: "Loading sources…", message: hit.title, preferredStyle: .alert)
        present(loading, animated: true)
        IOSMultiMoviesProvider.shared.sources(for: hit.url) { [weak self] sources in
            guard let self = self else { return }
            loading.dismiss(animated: true) {
                guard !sources.isEmpty else {
                    let alert = UIAlertController(title: "No sources found", message: "MultiMovies did not expose any server links for this title.", preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "OK", style: .default))
                    self.present(alert, animated: true)
                    return
                }
                let alert = UIAlertController(title: hit.title, message: "Choose a source", preferredStyle: .actionSheet)
                for source in sources {
                    let label = source.name + (source.playable ? "" : " • Not directly playable")
                    alert.addAction(UIAlertAction(title: label, style: .default) { _ in
                        guard source.playable else {
                            let info = UIAlertController(title: "Source unavailable", message: "This server did not expose a direct HLS or MP4 stream.", preferredStyle: .alert)
                            info.addAction(UIAlertAction(title: "OK", style: .default))
                            self.present(info, animated: true)
                            return
                        }
                        let player = AVPlayerViewController()
                        player.player = AVPlayer(url: source.url)
                        player.title = hit.title
                        self.present(player, animated: true) { player.player?.play() }
                    })
                }
                alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
                if let popover = alert.popoverPresentationController { popover.sourceView = self.view; popover.sourceRect = CGRect(x: self.view.bounds.midX, y: self.view.bounds.midY, width: 1, height: 1) }
                self.present(alert, animated: true)
            }
        }
    }
}
