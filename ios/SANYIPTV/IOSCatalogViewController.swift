import UIKit
import AVKit

struct IOSMediaItem {
    let title: String
    let url: URL
    let group: String
    let logoURL: URL?
}

final class IOSPlaylistStore {
    static let shared = IOSPlaylistStore()
    static let playlistURL = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u"
    private(set) var items: [IOSMediaItem] = []
    private(set) var lastError: String?
    private var isLoading = false
    private var observers = [NSObjectProtocol]()

    private init() {}

    func load(force: Bool = false) {
        guard !isLoading else { return }
        if !force && !items.isEmpty {
            NotificationCenter.default.post(name: .sanyPlaylistUpdated, object: nil)
            return
        }
        isLoading = true
        let candidates = [
            "https://cdn.jsdelivr.net/gh/Sany100/Sany-iptv@main/playlist.m3u",
            Self.playlistURL,
            "https://github.com/Sany100/Sany-iptv/raw/refs/heads/main/playlist.m3u"
        ]
        fetch(candidates: candidates, index: 0)
    }

    private func fetch(candidates: [String], index: Int) {
        guard index < candidates.count, let url = URL(string: candidates[index]) else {
            finish(items: [], error: "Playlist server is unavailable.")
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 18
        request.setValue("Mozilla/5.0 SANY-IPTV-iOS", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            if let data = data,
               let http = response as? HTTPURLResponse,
               (200...299).contains(http.statusCode),
               let body = String(data: data, encoding: .utf8),
               body.split(separator: "\n").first(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("#EXTM3U") }) != nil {
                let parsed = Self.parse(body)
                if !parsed.isEmpty {
                    self.finish(items: parsed, error: nil)
                    return
                }
            }
            self.fetch(candidates: candidates, index: index + 1)
        }.resume()
    }

    private func finish(items: [IOSMediaItem], error: String?) {
        DispatchQueue.main.async {
            self.isLoading = false
            if !items.isEmpty { self.items = items }
            self.lastError = error
            NotificationCenter.default.post(name: .sanyPlaylistUpdated, object: nil)
        }
    }

    private static func parse(_ text: String) -> [IOSMediaItem] {
        let lines = text.components(separatedBy: .newlines)
        var result = [IOSMediaItem]()
        var pending: (title: String, group: String, logo: String?)?
        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            if line.hasPrefix("#EXTINF:") {
                let group = attribute("group-title", in: line) ?? "Uncategorized"
                let logo = attribute("tvg-logo", in: line)
                let title = line.components(separatedBy: ",").last?.trimmingCharacters(in: .whitespaces) ?? "Unknown"
                pending = (title.isEmpty ? "Unknown" : title, group, logo)
            } else if !line.hasPrefix("#"), let meta = pending, let url = URL(string: line), ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
                result.append(IOSMediaItem(title: meta.title, url: url, group: meta.group,
                                           logoURL: meta.logo.flatMap { URL(string: $0) }))
                pending = nil
            }
        }
        return result
    }

    private static func attribute(_ name: String, in line: String) -> String? {
        let escaped = NSRegularExpression.escapedPattern(for: name)
        guard let regex = try? NSRegularExpression(pattern: "(?i)\\b\\(escaped)=\\\"([^\\\"]*)\\\""),
              let match = regex.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)),
              let range = Range(match.range(at: 1), in: line) else { return nil }
        return String(line[range])
    }
}

extension Notification.Name {
    static let sanyPlaylistUpdated = Notification.Name("SANYPlaylistUpdated")
}

enum IOSCatalogSection: String {
    case sports = "Live Sports"
    case movies = "Movies"
    case anime = "Anime"

    var symbols: String {
        switch self {
        case .sports: return "sportscourt"
        case .movies: return "film"
        case .anime: return "sparkles"
        }
    }

    func matches(_ item: IOSMediaItem) -> Bool {
        let value = (item.title + " " + item.group).lowercased()
        switch self {
        case .sports:
            return ["sport", "cricket", "football", "soccer", "basketball", "tennis", "live event", "playztv"].contains(where: { value.contains($0) })
        case .movies:
            return ["movie", "movies", "film", "vod", "cinema"].contains(where: value.contains)
        case .anime:
            return ["anime", "animation", "cartoon", "animé"].contains(where: value.contains)
        }
    }
}

final class IOSCatalogViewController: UITableViewController, UISearchBarDelegate {
    private let section: IOSCatalogSection
    private let searchBar = UISearchBar()
    private let refresh = UIRefreshControl()
    private var query = ""
    private var selectedGroup = "All"
    private var observer: NSObjectProtocol?
    private var filtered: [IOSMediaItem] = []

    init(section: IOSCatalogSection) {
        self.section = section
        super.init(style: .insetGrouped)
        title = section.rawValue
        tabBarItem = UITabBarItem(title: section.rawValue, image: UIImage(systemName: section.symbols), selectedImage: UIImage(systemName: section.symbols))
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = IOSAppStyle.background
        tableView.backgroundColor = IOSAppStyle.background
        tableView.separatorColor = UIColor.white.withAlphaComponent(0.09)
        tableView.rowHeight = 66
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "MediaItem")
        searchBar.placeholder = "Search " + section.rawValue
        searchBar.delegate = self
        searchBar.searchBarStyle = .minimal
        searchBar.tintColor = IOSAppStyle.accent
        searchBar.barStyle = IOSAppStyle.isDark ? .black : .default
        tableView.tableHeaderView = searchBar
        refresh.tintColor = IOSAppStyle.accent
        refresh.addTarget(self, action: #selector(refreshPlaylist), for: .valueChanged)
        tableView.refreshControl = refresh
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Refresh", style: .plain, target: self, action: #selector(refreshPlaylist))
        observer = NotificationCenter.default.addObserver(forName: .sanyPlaylistUpdated, object: nil, queue: .main) { [weak self] _ in
            self?.refresh.endRefreshing()
            self?.applyFilters()
        }
        IOSPlaylistStore.shared.load()
        applyFilters()
    }

    deinit {
        if let observer = observer { NotificationCenter.default.removeObserver(observer) }
    }

    @objc private func refreshPlaylist() { IOSPlaylistStore.shared.load(force: true) }

    private func applyFilters() {
        let all = IOSPlaylistStore.shared.items.filter { section.matches($0) }
        let groups = Array(Set(all.map { $0.group })).sorted()
        if selectedGroup != "All" && !groups.contains(selectedGroup) { selectedGroup = "All" }
        filtered = all.filter {
            (selectedGroup == "All" || $0.group == selectedGroup) &&
            (query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) || $0.group.localizedCaseInsensitiveContains(query))
        }
        tableView.reloadData()
        updateEmptyState()
    }

    private func updateEmptyState() {
        if filtered.isEmpty {
            let label = UILabel()
            label.textAlignment = .center
            label.numberOfLines = 0
            label.textColor = IOSAppStyle.secondaryText
            let total = IOSPlaylistStore.shared.items.count
            if total == 0 {
                label.text = IOSPlaylistStore.shared.lastError ?? "Loading playlist… Pull down to refresh."
            } else {
                label.text = "No \(section.rawValue) entries found in the current M3U playlist.\n\nThis section needs its native iOS provider integration."
            }
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
    }

    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) {
        query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        applyFilters()
    }

    override func numberOfSections(in tableView: UITableView) -> Int { 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { filtered.count }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String {
        "\(filtered.count) items • \(selectedGroup)"
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "MediaItem", for: indexPath)
        let item = filtered[indexPath.row]
        var config = UIListContentConfiguration.subtitleCell()
        config.text = item.title
        config.secondaryText = item.group
        config.textProperties.color = IOSAppStyle.primaryText
        config.secondaryTextProperties.color = IOSAppStyle.secondaryText
        config.image = UIImage(systemName: section == .sports ? "sportscourt" : (section == .movies ? "film" : "sparkles"))
        config.imageProperties.tintColor = IOSAppStyle.accent
        cell.contentConfiguration = config
        cell.backgroundColor = IOSAppStyle.card
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let item = filtered[indexPath.row]
        let player = AVPlayerViewController()
        player.player = AVPlayer(url: item.url)
        player.title = item.title
        present(player, animated: true) { player.player?.play() }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        "Swipe down to refresh • Tap an item to play"
    }
}

enum IOSAppStyle {
    static var isDark: Bool { UserDefaults.standard.string(forKey: "sany.ios.theme") != "light" }
    static var background: UIColor { isDark ? UIColor(red: 0.015, green: 0.035, blue: 0.09, alpha: 1) : UIColor(red: 0.95, green: 0.97, blue: 0.99, alpha: 1) }
    static var card: UIColor { isDark ? UIColor(red: 0.035, green: 0.075, blue: 0.14, alpha: 1) : .white }
    static var primaryText: UIColor { isDark ? .white : UIColor(red: 0.08, green: 0.11, blue: 0.17, alpha: 1) }
    static var secondaryText: UIColor { isDark ? .lightGray : UIColor(red: 0.35, green: 0.39, blue: 0.46, alpha: 1) }
    static var accent: UIColor { UIColor(red: 0.20, green: 0.72, blue: 1, alpha: 1) }
}

final class IOSSettingsViewController: UITableViewController {
    private let rows = ["Appearance", "Refresh playlist", "Playlist source", "About SANY IPTV"]

    init() {
        super.init(style: .insetGrouped)
        title = "Settings"
        tabBarItem = UITabBarItem(title: "Settings", image: UIImage(systemName: "gearshape"), selectedImage: UIImage(systemName: "gearshape.fill"))
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = IOSAppStyle.background
        tableView.backgroundColor = IOSAppStyle.background
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "Setting")
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { rows.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "Setting", for: indexPath)
        var config = UIListContentConfiguration.valueCell()
        config.text = rows[indexPath.row]
        config.secondaryText = indexPath.row == 0 ? (IOSAppStyle.isDark ? "Night mode" : "Day mode") : nil
        config.textProperties.color = IOSAppStyle.primaryText
        config.secondaryTextProperties.color = IOSAppStyle.secondaryText
        cell.contentConfiguration = config
        cell.backgroundColor = IOSAppStyle.card
        cell.accessoryType = .disclosureIndicator
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        switch indexPath.row {
        case 0:
            let alert = UIAlertController(title: "App appearance", message: nil, preferredStyle: .actionSheet)
            alert.addAction(UIAlertAction(title: "Night mode", style: .default) { _ in self.setTheme("dark") })
            alert.addAction(UIAlertAction(title: "Day mode", style: .default) { _ in self.setTheme("light") })
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            if let popover = alert.popoverPresentationController { popover.sourceView = tableView; popover.sourceRect = tableView.rectForRow(at: indexPath) }
            present(alert, animated: true)
        case 1:
            IOSPlaylistStore.shared.load(force: true)
            let alert = UIAlertController(title: "Playlist refresh", message: "The playlist is being refreshed.", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            present(alert, animated: true)
        case 2:
            let alert = UIAlertController(title: "Playlist source", message: IOSPlaylistStore.playlistURL, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            present(alert, animated: true)
        default:
            let alert = UIAlertController(title: "SANY IPTV", message: "Native iPhone edition • Separate from Android Phone and Android TV apps.", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            present(alert, animated: true)
        }
    }
    private func setTheme(_ value: String) {
        UserDefaults.standard.set(value, forKey: "sany.ios.theme")
        tableView.reloadData()
        view.backgroundColor = IOSAppStyle.background
        tableView.backgroundColor = IOSAppStyle.background
    }
}
