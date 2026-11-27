import UIKit
import AVKit

struct IOSAnimeItem {
    let id: Int
    let slug: String
    let title: String
    let poster: URL?
    let format: String
    let year: Int?
    let score: Double?
    let description: String?
}

struct IOSAnimeEpisode {
    let number: Int
    let title: String
    let sub: Bool
    let dub: Bool
}

struct IOSAnimeSource {
    let title: String
    let url: URL
    let quality: Int?
}

final class IOSAniLightProvider {
    static let shared = IOSAniLightProvider()
    private let session = URLSession(configuration: .default)
    private let defaultSite = "https://anilight.live"
    private var site = "https://anilight.live"
    private var api = "https://api.anilight.live/api"
    private var sessionToken: String?
    private var sessionExpiry: TimeInterval = 0
    private var homepageCache: [[String: Any]] = []
    private var homepageExpiry: TimeInterval = 0

    private let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 Version/16.0 Mobile/15E148 Safari/604.1"

    private init() {}

    private func request(_ url: URL, method: String = "GET", json: [String: Any]? = nil, extraHeaders: [String: String] = [:], completion: @escaping (Data?, URLResponse?, Error?) -> Void) {
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        req.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        req.setValue(site + "/", forHTTPHeaderField: "Referer")
        for (key, value) in extraHeaders { req.setValue(value, forHTTPHeaderField: key) }
        if let json = json {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: json, options: [])
        }
        session.dataTask(with: req, completionHandler: completion).resume()
    }

    private func getJSON(_ path: String, completion: @escaping ([String: Any]?) -> Void) {
        guard let url = URL(string: api + path) else { completion(nil); return }
        request(url) { data, response, _ in
            guard let data = data, let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                DispatchQueue.main.async { completion(nil) }; return
            }
            DispatchQueue.main.async { completion(object) }
        }
    }

    private func media(_ raw: Any?) -> [IOSAnimeItem] {
        guard let rows = raw as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            guard let id = row["id"] as? Int,
                  let slug = row["slug"] as? String, !slug.isEmpty else { return nil }
            let titles = row["title"] as? [String: Any] ?? [:]
            let title = (titles["english"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? (titles["romaji"] as? String)
                ?? (titles["userPreferred"] as? String)
                ?? (titles["native"] as? String)
                ?? "Untitled Anime"
            let covers = row["coverImage"] as? [String: Any] ?? [:]
            let posterString = (covers["extraLarge"] as? String) ?? (covers["large"] as? String) ?? (covers["medium"] as? String)
            return IOSAnimeItem(id: id, slug: slug, title: title, poster: posterString.flatMap(URL.init(string:)),
                                format: row["format"] as? String ?? "TV",
                                year: row["seasonYear"] as? Int,
                                score: row["averageScore"] as? Double,
                                description: row["description"] as? String)
        }
    }

    func homepage(completion: @escaping ([(String, [IOSAnimeItem])]) -> Void) {
        if Date().timeIntervalSince1970 < homepageExpiry, !homepageCache.isEmpty {
            let data = homepageCache
            DispatchQueue.main.async { completion(self.homeSections(data)) }
            return
        }
        getJSON("/homepage") { [weak self] root in
            guard let self = self, let root = root else { completion([]); return }
            let sections: [(String, String)] = [
                ("Trending Now", "trending"), ("Most Popular", "popular"),
                ("Anime Movies", "popularMovies"), ("This Season", "seasonal"),
                ("Recently Added", "recentlyAddedEpisodes"), ("Upcoming", "upcoming")
            ]
            self.homepageCache = sections.map { name, key in
                let section = root[key] as? [String: Any]
                return ["name": name, "items": (section?["media"] as? [[String: Any]]) ?? (root[key] as? [[String: Any]]) ?? []]
            }
            self.homepageExpiry = Date().timeIntervalSince1970 + 300
            completion(self.homeSections(self.homepageCache))
        }
    }

    private func homeSections(_ raw: [[String: Any]]) -> [(String, [IOSAnimeItem])] {
        raw.compactMap { row in
            guard let name = row["name"] as? String else { return nil }
            return (name, media(row["items"]))
        }.filter { !$0.1.isEmpty }
    }

    func filter(genre: String?, topRated: Bool = false, completion: @escaping ([IOSAnimeItem]) -> Void) {
        var components = URLComponents(string: api + "/filter")!
        var queryItems = [URLQueryItem(name: "page", value: "1"),
                          URLQueryItem(name: "perPage", value: "30"),
                          URLQueryItem(name: "sort", value: topRated ? "SCORE_DESC" : "POPULARITY_DESC"),
                          URLQueryItem(name: "hideAdult", value: "true")]
        if let genre = genre { queryItems.append(URLQueryItem(name: "genres", value: genre)) }
        components.queryItems = queryItems
        guard let url = components.url else { completion([]); return }
        request(url) { data, response, _ in
            let root = (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [String: Any]
            let items = self.media(root?["media"])
            DispatchQueue.main.async { completion(items) }
        }
    }

    func search(_ query: String, completion: @escaping ([IOSAnimeItem]) -> Void) {
        guard let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else { completion([]); return }
        guard let url = URL(string: api + "/search?q=\(encoded)&hideAdult=true") else {
            DispatchQueue.main.async { completion([]) }
            return
        }
        request(url) { data, response, _ in
            let rows = (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [[String: Any]] ?? []
            let items = self.media(rows.filter { $0["isAdult"] as? Bool != true })
            DispatchQueue.main.async { completion(items) }
        }
    }

    func detail(slug: String, completion: @escaping (IOSAnimeItem?, [IOSAnimeEpisode]) -> Void) {
        getJSON("/anime/\(slug)") { [weak self] root in
            guard let self = self, let root = root else { completion(nil, []); return }
            let anime = self.media([root]).first
            self.getJSON("/watch/\(slug)") { watch in
                let rows = watch?["episodes"] as? [[String: Any]] ?? []
                var episodes = rows.compactMap { row -> IOSAnimeEpisode? in
                    guard let number = row["number"] as? Int else { return nil }
                    return IOSAnimeEpisode(number: number, title: row["title"] as? String ?? "Episode \(number)",
                                           sub: row["sub"] as? Bool ?? false, dub: row["dub"] as? Bool ?? false)
                }
                if episodes.isEmpty, (root["format"] as? String) == "MOVIE" {
                    episodes = [IOSAnimeEpisode(number: 1, title: "Movie", sub: true, dub: false)]
                }
                completion(anime, episodes)
            }
        }
    }

    private func obtainSession(force: Bool, completion: @escaping (String?) -> Void) {
        if !force, let token = sessionToken, Date().timeIntervalSince1970 * 1000 < sessionExpiry - 60000 {
            completion(token); return
        }
        guard let url = URL(string: api + "/sources/verify") else { completion(nil); return }
        request(url, method: "POST", json: ["token": "direct"]) { data, response, _ in
            let root = (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [String: Any]
            let token = root?["sessionToken"] as? String
            if let token = token, !token.isEmpty {
                self.sessionToken = token
                self.sessionExpiry = root?["expiresAt"] as? TimeInterval ?? (Date().timeIntervalSince1970 * 1000 + 86400000)
                completion(token)
            } else { completion(self.sessionToken) }
        }
    }

    func sources(animeID: Int, episode: Int, language: String, completion: @escaping ([IOSAnimeSource]) -> Void) {
        obtainSession(force: false) { [weak self] token in
            guard let self = self, let token = token else { completion([]); return }
            self.fetchSources(animeID: animeID, episode: episode, language: language, backup: false, token: token) { own in
                if !own.isEmpty { completion(own); return }
                self.obtainSession(force: true) { renewed in
                    guard let renewed = renewed else { completion([]); return }
                    self.fetchSources(animeID: animeID, episode: episode, language: language, backup: true, token: renewed, completion: completion)
                }
            }
        }
    }

    private func fetchSources(animeID: Int, episode: Int, language: String, backup: Bool, token: String, completion: @escaping ([IOSAnimeSource]) -> Void) {
        let suffix = backup ? "&backup=true" : ""
        let path = "/sources?id=\(animeID)&episode=\(episode)&language=\(language)\(suffix)"
        guard let url = URL(string: api + path) else { completion([]); return }
        request(url, extraHeaders: ["x-source-session": token]) { data, response, _ in
            guard let data = data, let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let rows = root["sources"] as? [[String: Any]] else {
                DispatchQueue.main.async { completion([]) }
                return
            }
            let tokens = rows.flatMap { row -> [(String, String?)] in
                let raw = row["url"]
                let values = (raw as? [String]) ?? ((raw as? String).map { [$0] } ?? [])
                return values.map { ($0, row["quality"] as? String) }
            }
            self.resolveTokens(tokens, index: 0, seen: [], results: [], completion: completion)
        }
    }

    private func resolveTokens(_ tokens: [(String, String?)], index: Int, seen: Set<String>, results: [IOSAnimeSource], completion: @escaping ([IOSAnimeSource]) -> Void) {
        if index >= tokens.count { DispatchQueue.main.async { completion(results) }; return }
        let (token, quality) = tokens[index]
        guard let url = URL(string: api + "/lb/e/" + token) else {
            resolveTokens(tokens, index: index + 1, seen: seen, results: results, completion: completion); return
        }
        request(url, method: "HEAD") { data, response, _ in
            let finalURL = response?.url
            var nextSeen = seen
            var nextResults = results
            if let finalURL = finalURL, finalURL.scheme?.hasPrefix("http") == true, !nextSeen.contains(finalURL.absoluteString) {
                let height = quality.flatMap { Int($0.lowercased().replacingOccurrences(of: "p", with: "")) }
                nextSeen.insert(finalURL.absoluteString)
                nextResults.append(IOSAnimeSource(title: "AniLight \(height.map { "\($0)p" } ?? "Source")", url: finalURL, quality: height))
            }
            self.resolveTokens(tokens, index: index + 1, seen: nextSeen, results: nextResults, completion: completion)
        }
    }
}

final class IOSAnimeHomeViewController: UITableViewController, UISearchBarDelegate {
    private var sections: [(String, [IOSAnimeItem])] = []
    private var searchResults: [IOSAnimeItem]?
    private let searchBar = UISearchBar()
    private let spinner = UIActivityIndicatorView(style: .gray)

    init() {
        super.init(style: .grouped)
        title = "Anime"
        tabBarItem = UITabBarItem(title: "Anime", image: IOSIcon.image("sparkles"), selectedImage: IOSIcon.image("sparkles"))
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = IOSAppStyle.background
        tableView.backgroundColor = IOSAppStyle.background
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "AnimeItem")
        tableView.rowHeight = 76
        searchBar.placeholder = "Search AniLight anime"
        searchBar.delegate = self
        searchBar.searchBarStyle = .minimal
        tableView.tableHeaderView = searchBar
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: "Categories", style: .plain, target: self, action: #selector(showCategories))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Refresh", style: .plain, target: self, action: #selector(refreshHome))
        loadHome()
    }

    @objc private func showCategories() {
        let names = ["Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mecha", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"]
        let alert = UIAlertController(title: "Anime categories", message: nil, preferredStyle: .actionSheet)
        alert.addAction(UIAlertAction(title: "Top Rated", style: .default) { _ in
            self.loadFilter(genre: nil, topRated: true)
        })
        for name in names {
            alert.addAction(UIAlertAction(title: name, style: .default) { _ in
                self.loadFilter(genre: name, topRated: false)
            })
        }
        alert.addAction(UIAlertAction(title: "All home sections", style: .default) { _ in self.loadHome() })
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func loadFilter(genre: String?, topRated: Bool) {
        IOSAniLightProvider.shared.filter(genre: genre, topRated: topRated) { [weak self] items in
            guard let self = self else { return }
            self.searchResults = items
            self.tableView.reloadData()
        }
    }

    private func loadHome() {
        IOSAniLightProvider.shared.homepage { [weak self] rows in
            self?.sections = rows
            self?.searchResults = nil
            self?.tableView.reloadData()
        }
    }

    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { searchResults = nil; tableView.reloadData(); return }
        IOSAniLightProvider.shared.search(query) { [weak self] items in
            guard let self = self, self.searchBar.text?.trimmingCharacters(in: .whitespacesAndNewlines) == query else { return }
            self.searchResults = items
            self.tableView.reloadData()
        }
    }

    override func numberOfSections(in tableView: UITableView) -> Int { searchResults == nil ? sections.count : 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        searchResults == nil ? sections[section].1.count : (searchResults?.count ?? 0)
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        searchResults == nil ? sections[section].0 : "Search results"
    }
    private func item(at indexPath: IndexPath) -> IOSAnimeItem {
        searchResults == nil ? sections[indexPath.section].1[indexPath.row] : searchResults![indexPath.row]
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "AnimeItem", for: indexPath)
        let item = self.item(at: indexPath)
        cell.textLabel?.text = item.title + (item.year.map { " • \($0)" } ?? "")
        cell.textLabel?.textColor = IOSAppStyle.primaryText
        cell.textLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        cell.imageView?.image = IOSIcon.image("play.rectangle")
        cell.imageView?.tintColor = IOSAppStyle.accent
        cell.backgroundColor = IOSAppStyle.card
        cell.accessoryType = .disclosureIndicator
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let item = self.item(at: indexPath)
        let detail = IOSAnimeDetailViewController(slug: item.slug, titleText: item.title)
        navigationController?.pushViewController(detail, animated: true)
    }
}

final class IOSAnimeDetailViewController: UITableViewController {
    private let slug: String
    private let titleText: String
    private var anime: IOSAnimeItem?
    private var episodes: [IOSAnimeEpisode] = []

    init(slug: String, titleText: String) {
        self.slug = slug
        self.titleText = titleText
        super.init(style: .grouped)
        title = titleText
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = IOSAppStyle.background
        tableView.backgroundColor = IOSAppStyle.background
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "Episode")
        IOSAniLightProvider.shared.detail(slug: slug) { [weak self] anime, episodes in
            self?.anime = anime
            self?.episodes = episodes
            self?.tableView.reloadData()
        }
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { episodes.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        episodes.isEmpty ? "No episodes returned by AniLight" : "Episodes"
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "Episode", for: indexPath)
        let episode = episodes[indexPath.row]
        let audio = [episode.sub ? "SUB" : nil, episode.dub ? "DUB" : nil].compactMap { $0 }.joined(separator: " / ")
        cell.textLabel?.text = episode.title + (audio.isEmpty ? "" : " • " + audio)
        cell.textLabel?.textColor = IOSAppStyle.primaryText
        cell.backgroundColor = IOSAppStyle.card
        cell.accessoryType = .disclosureIndicator
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let episode = episodes[indexPath.row]
        let alert = UIAlertController(title: episode.title, message: "Choose audio", preferredStyle: .actionSheet)
        if episode.sub { alert.addAction(UIAlertAction(title: "SUB", style: .default) { _ in self.loadSources(episode, language: "sub") }) }
        if episode.dub { alert.addAction(UIAlertAction(title: "DUB", style: .default) { _ in self.loadSources(episode, language: "dub") }) }
        if !episode.sub && !episode.dub { alert.addAction(UIAlertAction(title: "SUB", style: .default) { _ in self.loadSources(episode, language: "sub") }) }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        if let popover = alert.popoverPresentationController { popover.sourceView = tableView; popover.sourceRect = tableView.rectForRow(at: indexPath) }
        present(alert, animated: true)
    }

    private func loadSources(_ episode: IOSAnimeEpisode, language: String) {
        guard let animeID = anime?.id else { return }
        let loading = UIAlertController(title: "Finding sources…", message: "AniLight is resolving available streams.", preferredStyle: .alert)
        present(loading, animated: true)
        IOSAniLightProvider.shared.sources(animeID: animeID, episode: episode.number, language: language) { [weak self] sources in
            guard let self = self else { return }
            loading.dismiss(animated: true) {
                guard !sources.isEmpty else {
                    let error = UIAlertController(title: "No playable source", message: "AniLight did not return a working stream. Try refreshing or another episode.", preferredStyle: .alert)
                    error.addAction(UIAlertAction(title: "OK", style: .default))
                    self.present(error, animated: true)
                    return
                }
                let choices = UIAlertController(title: "Choose source", message: nil, preferredStyle: .actionSheet)
                for source in sources {
                    choices.addAction(UIAlertAction(title: source.title, style: .default) { _ in
                        let player = AVPlayerViewController()
                        player.player = AVPlayer(url: source.url)
                        player.title = episode.title
                        self.present(player, animated: true) { player.player?.play() }
                    })
                }
                choices.addAction(UIAlertAction(title: "Cancel", style: .cancel))
                if let popover = choices.popoverPresentationController { popover.sourceView = self.view; popover.sourceRect = CGRect(x: self.view.bounds.midX, y: self.view.bounds.midY, width: 1, height: 1) }
                self.present(choices, animated: true)
            }
        }
    }
}
