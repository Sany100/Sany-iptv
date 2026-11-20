import UIKit
import AVKit
import BackgroundTasks
import zlib

private struct SANYChannel {
    let name: String
    let streamURL: URL
    let group: String
    let logoURL: URL?
}

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var mainTabs: UITabBarController?
    private var maintenanceTimer: Timer?
    private let maintenanceURL = URL(string: "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/maintenance.json")!
    private let backgroundEPGTaskID = "com.sany.iptv.ios.epg-refresh"

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        let tabs = UITabBarController()
        tabs.viewControllers = [
            UINavigationController(rootViewController: LiveTVViewController()),
            UINavigationController(rootViewController: CheckerViewController()),
            UINavigationController(rootViewController: PlaybackSettingsViewController())
        ]
        tabs.tabBar.barTintColor = UIColor(red: 0.025, green: 0.055, blue: 0.12, alpha: 1)
        tabs.tabBar.tintColor = .cyan
        tabs.tabBar.unselectedItemTintColor = .lightGray
        window.rootViewController = tabs
        window.makeKeyAndVisible()
        self.window = window
        self.mainTabs = tabs
        if #available(iOS 13.0, *) {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: backgroundEPGTaskID, using: nil) { task in
                self.handleBackgroundEPG(task: task as! BGAppRefreshTask)
            }
            scheduleBackgroundEPG()
        }
        checkMaintenanceAccess()
        maintenanceTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in self?.checkMaintenanceAccess() }
        return true
    }

    private func checkMaintenanceAccess() {
        var request = URLRequest(url: maintenanceURL)
        request.timeoutInterval = 8
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        URLSession.shared.dataTask(with: request) { [weak self] data, _, error in
            guard let self = self, error == nil, let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            let enabled = json["enabled"] as? Bool ?? false
            let message = json["message"] as? String ?? "আমাদের অ্যাপে উন্নয়নমূলক কাজ চলছে। পরে আবার চেষ্টা করুন।"
            let allowed = json["allowed_ios_devices"] as? [String] ?? []
            let deviceID = Self.installationID()
            DispatchQueue.main.async {
                guard let window = self.window else { return }
                if enabled && !allowed.contains(deviceID) {
                    if !(window.rootViewController is MaintenanceViewController) {
                        window.rootViewController = MaintenanceViewController(message: message, deviceID: deviceID)
                    }
                } else if window.rootViewController is MaintenanceViewController {
                    window.rootViewController = self.mainTabs
                }
            }
        }.resume()
    }

    private static func installationID() -> String {
        let key = "sany.ios.installationID"
        if let value = UserDefaults.standard.string(forKey: key) { return value }
        let value = UUID().uuidString.lowercased()
        UserDefaults.standard.set(value, forKey: key)
        return value
    }

    @available(iOS 13.0, *)
    private func scheduleBackgroundEPG() {
        let request = BGAppRefreshTaskRequest(identifier: backgroundEPGTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 24 * 60 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    @available(iOS 13.0, *)
    private func handleBackgroundEPG(task: BGAppRefreshTask) {
        scheduleBackgroundEPG()
        let operation = EPGRepository.update { result in
            task.setTaskCompleted(success: (try? result.get()) != nil)
        }
        task.expirationHandler = { operation.cancel() }
    }
}

private final class MaintenanceViewController: UIViewController {
    private let message: String
    private let deviceID: String
    init(message: String, deviceID: String) { self.message = message; self.deviceID = deviceID; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.018, green: 0.035, blue: 0.085, alpha: 1)
        let heading = UILabel(); heading.text = "SANY IPTV — MAINTENANCE"; heading.textColor = .white; heading.font = .boldSystemFont(ofSize: 22); heading.textAlignment = .center
        let detail = UILabel(); detail.text = message; detail.textColor = .white; detail.numberOfLines = 0; detail.textAlignment = .center
        let id = UILabel(); id.text = "iPhone Installation ID\n\(deviceID)"; id.textColor = .lightGray; id.numberOfLines = 0; id.textAlignment = .center; id.font = .systemFont(ofSize: 12)
        let copy = UIButton(type: .system); copy.setTitle("COPY DEVICE ID (ADMIN)", for: .normal); copy.addTarget(self, action: #selector(copyID), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [heading, detail, id, copy]); stack.axis = .vertical; stack.spacing = 22; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 26), stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -26), stack.centerYAnchor.constraint(equalTo: view.centerYAnchor)])
    }
    @objc private func copyID() { UIPasteboard.general.string = deviceID; let alert = UIAlertController(title: "Copied", message: "Add this ID to allowed_ios_devices in maintenance.json to allow this iPhone during maintenance.", preferredStyle: .alert); alert.addAction(UIAlertAction(title: "OK", style: .default)); present(alert, animated: true) }
}

private enum SANYStyle {
    static var background: UIColor {
        (UserDefaults.standard.string(forKey: "sany.ios.theme") == "Day" || (UserDefaults.standard.string(forKey: "sany.ios.theme") == "System default" && UITraitCollection.current.userInterfaceStyle == .light))
            ? UIColor(red: 0.95, green: 0.96, blue: 0.98, alpha: 1)
            : UIColor(red: 0.018, green: 0.035, blue: 0.085, alpha: 1)
    }
    static var panel: UIColor {
        (UserDefaults.standard.string(forKey: "sany.ios.theme") == "Day" || (UserDefaults.standard.string(forKey: "sany.ios.theme") == "System default" && UITraitCollection.current.userInterfaceStyle == .light))
            ? .white : UIColor(red: 0.045, green: 0.075, blue: 0.14, alpha: 1)
    }
    static var accent: UIColor {
        switch UserDefaults.standard.string(forKey: "sany.ios.accent") ?? "Blue" {
        case "Teal": return UIColor(red: 0.0, green: 0.75, blue: 0.59, alpha: 1)
        case "Purple": return UIColor(red: 0.65, green: 0.42, blue: 1.0, alpha: 1)
        case "Orange": return UIColor(red: 1.0, green: 0.55, blue: 0.2, alpha: 1)
        default: return UIColor(red: 0.1, green: 0.65, blue: 1.0, alpha: 1)
        }
    }
    static var secondary: UIColor { UIColor(red: 0.62, green: 0.7, blue: 0.82, alpha: 1) }
    static func applyTheme(_ value: String) { UserDefaults.standard.set(value, forKey: "sany.ios.theme") }
}

private final class PlaylistStore {
    static let shared = PlaylistStore()
    private(set) var channels: [SANYChannel] = []
    private var loadedSource = ""
    private var loading = false
    private var callbacks: [(Result<[SANYChannel], Error>) -> Void] = []
    private var pendingSource = ""
    private(set) var loadedFromCache = false

    private func cacheURL(for source: String) -> URL {
        let encoded = Data(source.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "=", with: "")
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Playlists", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent(encoded + ".m3u")
    }

    var activePlaylist: String {
        UserDefaults.standard.string(forKey: "sany.ios.activePlaylist")
            ?? "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u"
    }

    func load(completion: @escaping (Result<[SANYChannel], Error>) -> Void) {
        load(from: activePlaylist, forceRefresh: false, completion: completion)
    }

    func load(from source: String, forceRefresh: Bool = true, completion: @escaping (Result<[SANYChannel], Error>) -> Void) {
        if loading {
            callbacks.append(completion)
            return
        }
        if !forceRefresh && !channels.isEmpty && loadedSource == source {
            completion(.success(channels))
            return
        }
        loading = true
        pendingSource = source
        callbacks.append(completion)

        if source.hasPrefix("file://"), let url = URL(string: source) {
            DispatchQueue.global(qos: .userInitiated).async {
                let result: Result<[SANYChannel], Error>
                do {
                    let text = try String(contentsOf: url, encoding: .utf8)
                    EPGRepository.detectPlaylistEPG(text)
                    let parsed = self.parse(text)
                    if parsed.isEmpty { throw NSError(domain: "SANYPlaylist", code: 1, userInfo: [NSLocalizedDescriptionKey: "প্লেলিস্টে কোনো চ্যানেল পাওয়া যায়নি।"]) }
                    result = .success(parsed)
                } catch { result = .failure(error) }
                self.finish(result, source: source)
            }
            return
        }

        guard let url = URL(string: source), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
            finish(.failure(NSError(domain: "SANYPlaylist", code: 2, userInfo: [NSLocalizedDescriptionKey: "সঠিক HTTP/HTTPS playlist URL দিন।"])), source: source)
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue("SANY-IPTV-iOS", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { data, response, error in
            var result: Result<[SANYChannel], Error>
            var fromCache = false
            if error == nil, let data = data,
               let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
               let text = String(data: data, encoding: .utf8) {
                let parsed = self.parse(text)
                if parsed.isEmpty {
                    result = .failure(NSError(domain: "SANYPlaylist", code: 3, userInfo: [NSLocalizedDescriptionKey: "প্লেলিস্টে কোনো playable channel পাওয়া যায়নি।"]))
                } else {
                    try? data.write(to: self.cacheURL(for: source), options: .atomic)
                    result = .success(parsed)
                }
            } else {
                let cache = self.cacheURL(for: source)
                if let cached = try? Data(contentsOf: cache), let text = String(data: cached, encoding: .utf8) {
                    let parsed = self.parse(text)
                    if !parsed.isEmpty { result = .success(parsed); fromCache = true }
                    else { result = .failure(error ?? NSError(domain: "SANYPlaylist", code: 4, userInfo: [NSLocalizedDescriptionKey: "Cached playlist is invalid."])) }
                } else {
                    result = .failure(error ?? NSError(domain: "SANYPlaylist", code: 4, userInfo: [NSLocalizedDescriptionKey: "Playlist server সঠিক উত্তর দেয়নি এবং কোনো cache নেই।"]))
                }
            }
            self.loadedFromCache = fromCache
            self.finish(result, source: source)
        }.resume()
    }

    func importPlaylist(text: String, name: String, completion: @escaping (Result<[SANYChannel], Error>) -> Void) {
        EPGRepository.detectPlaylistEPG(text)
        let parsed = parse(text)
        guard !parsed.isEmpty else {
            completion(.failure(NSError(domain: "SANYPlaylist", code: 5, userInfo: [NSLocalizedDescriptionKey: "ফাইলে valid M3U channel পাওয়া যায়নি।"])))
            return
        }
        let safeName = name.replacingOccurrences(of: "/", with: "_")
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(safeName)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            let source = url.absoluteString
            try text.write(to: cacheURL(for: source), atomically: true, encoding: .utf8)
            channels = parsed
            loadedFromCache = false
            var saved = UserDefaults.standard.stringArray(forKey: "sany.ios.playlists") ?? []
            if !saved.contains(source) { saved.append(source); UserDefaults.standard.set(saved, forKey: "sany.ios.playlists") }
            loadedSource = source
            UserDefaults.standard.set(source, forKey: "sany.ios.activePlaylist")
            completion(.success(parsed))
        } catch { completion(.failure(error)) }
    }

    private func finish(_ result: Result<[SANYChannel], Error>, source: String) {
        DispatchQueue.main.async {
            if case .success(let items) = result {
                self.channels = items
                self.loadedSource = source
                UserDefaults.standard.set(source, forKey: "sany.ios.activePlaylist")
            }
            self.loading = false
            let pending = self.callbacks
            self.callbacks.removeAll()
            pending.forEach { $0(result) }
        }
    }

    private func parse(_ text: String) -> [SANYChannel] {
        var result: [SANYChannel] = []
        var name = ""
        var group = "Other"
        var logo: URL?
        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.lowercased().hasPrefix("#extinf") {
                let pattern = #"([\w-]+)="([^"]*)""#
                var attrs: [String: String] = [:]
                if let regex = try? NSRegularExpression(pattern: pattern) {
                    let range = NSRange(line.startIndex..<line.endIndex, in: line)
                    for match in regex.matches(in: line, range: range) where match.numberOfRanges == 3 {
                        if let kr = Range(match.range(at: 1), in: line), let vr = Range(match.range(at: 2), in: line) {
                            attrs[String(line[kr]).lowercased()] = String(line[vr])
                        }
                    }
                }
                group = attrs["group-title"].flatMap { $0.isEmpty ? nil : $0 } ?? "Other"
                logo = attrs["tvg-logo"].flatMap { URL(string: $0) }
                name = line.components(separatedBy: ",").last?.trimmingCharacters(in: .whitespaces) ?? "Unknown channel"
            } else if !line.isEmpty && !line.hasPrefix("#"),
                      let url = URL(string: line),
                      ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
                let fallback = url.lastPathComponent.isEmpty ? "Channel \(result.count + 1)" : url.lastPathComponent
                result.append(SANYChannel(name: name.isEmpty ? fallback : name, streamURL: url, group: group, logoURL: logo))
                name = ""
                group = "Other"
                logo = nil
            }
        }
        var seen = Set<String>()
        return result.filter { seen.insert($0.streamURL.absoluteString).inserted }
    }
}

private class BaseViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = SANYStyle.background
        navigationController?.navigationBar.barTintColor = SANYStyle.panel
        navigationController?.navigationBar.titleTextAttributes = [.foregroundColor: UIColor.white, .font: UIFont.boldSystemFont(ofSize: 19)]
        navigationController?.navigationBar.tintColor = SANYStyle.accent
    }

    func showMessage(_ message: String) {
        let alert = UIAlertController(title: "SANY IPTV", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "ঠিক আছে", style: .default))
        present(alert, animated: true)
    }
}

private final class LiveTVViewController: BaseViewController, UITableViewDataSource, UITableViewDelegate, UISearchBarDelegate {
    private let tableView = UITableView(frame: .zero, style: .plain)
    private let searchBar = UISearchBar()
    private let filter = UISegmentedControl(items: ["সব", "পছন্দের"])
    private var channels: [SANYChannel] = []
    private var visible: [SANYChannel] = []
    private var favorites = Set(UserDefaults.standard.stringArray(forKey: "sany.ios.favorites") ?? [])
    private var selectedGroup: String?
    private let spinner = UIActivityIndicatorView(style: .whiteLarge)
    private var retryObserver: NSObjectProtocol?
    private var retryCount = 0

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "SANY IPTV"
        tabBarItem = UITabBarItem(title: "Live TV", image: nil, tag: 0)
        searchBar.placeholder = "চ্যানেল খুঁজুন"
        searchBar.delegate = self
        searchBar.barStyle = .black
        searchBar.searchBarStyle = .minimal
        searchBar.tintColor = SANYStyle.accent
        filter.selectedSegmentIndex = 0
        filter.addTarget(self, action: #selector(filterChanged), for: .valueChanged)
        filter.backgroundColor = SANYStyle.panel
        filter.tintColor = SANYStyle.accent
        filter.setTitleTextAttributes([.foregroundColor: UIColor.white], for: .normal)
        filter.setTitleTextAttributes([.foregroundColor: UIColor.white], for: .selected)
        let header = UIStackView(arrangedSubviews: [searchBar, filter])
        header.axis = .vertical
        header.spacing = 4
        header.isLayoutMarginsRelativeArrangement = true
        header.layoutMargins = UIEdgeInsets(top: 4, left: 8, bottom: 8, right: 8)
        header.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: 104)
        tableView.tableHeaderView = header
        tableView.backgroundColor = SANYStyle.background
        tableView.separatorColor = UIColor.white.withAlphaComponent(0.08)
        tableView.dataSource = self
        tableView.delegate = self
        tableView.rowHeight = 68
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "channel")
        tableView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(tableView)
        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "রিফ্রেশ", style: .plain, target: self, action: #selector(reloadPlaylist))
        reloadPlaylist()
    }

    @objc private func reloadPlaylist() {
        PlaylistStore.shared.load { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let items):
                self.channels = items
                self.applyFilters()
            case .failure(let error):
                self.showMessage("চ্যানেল লোড হয়নি: \(error.localizedDescription)")
            }
        }
    }

    @objc private func filterChanged() { applyFilters() }
    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) { applyFilters() }

    private func applyFilters() {
        let query = searchBar.text ?? ""
        visible = channels.filter {
            let favoriteMatch = filter.selectedSegmentIndex == 0 || favorites.contains($0.streamURL.absoluteString)
            return favoriteMatch && (query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) || $0.group.localizedCaseInsensitiveContains(query))
        }
        tableView.reloadData()
        title = "SANY IPTV (\(visible.count))"
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { visible.count }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let channel = visible[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: "channel", for: indexPath)
        cell.backgroundColor = SANYStyle.panel
        cell.textLabel?.text = channel.name + "\n" + channel.group
        cell.textLabel?.numberOfLines = 2
        cell.textLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)
        cell.textLabel?.textColor = .white
        cell.detailTextLabel?.textColor = SANYStyle.secondary
        cell.accessoryType = favorites.contains(channel.streamURL.absoluteString) ? .checkmark : .disclosureIndicator
        cell.tintColor = SANYStyle.accent
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let channel = visible[indexPath.row]
        if let observer = retryObserver {
            NotificationCenter.default.removeObserver(observer)
            retryObserver = nil
        }
        retryCount = 0
        let item = AVPlayerItem(url: channel.streamURL)
        let defaults = UserDefaults.standard
        let quality = defaults.string(forKey: "sany.ios.quality") ?? "Auto"
        switch quality {
        case "480p": item.preferredPeakBitRate = 1_200_000
        case "720p": item.preferredPeakBitRate = 2_500_000
        case "1080p": item.preferredPeakBitRate = 5_000_000
        default: item.preferredPeakBitRate = defaults.bool(forKey: "sany.ios.lowDataMode") ? 650_000 : 0
        }
        let buffer = defaults.string(forKey: "sany.ios.buffer") ?? "Balanced"
        item.preferredForwardBufferDuration = buffer == "Low" ? 2.5 : (buffer == "High" ? 6.0 : 4.0)
        let avPlayer = AVPlayer(playerItem: item)
        avPlayer.automaticallyWaitsToMinimizeStalling = true
        let player = AVPlayerViewController()
        player.player = avPlayer
        player.allowsPictureInPicturePlayback = defaults.object(forKey: "sany.ios.pictureInPicture") as? Bool ?? true
        player.title = channel.name
        retryObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self, weak avPlayer] _ in
            guard let self = self, let avPlayer = avPlayer,
                  defaults.object(forKey: "sany.ios.autoRetry") as? Bool ?? true,
                  self.retryCount < 2 else { return }
            self.retryCount += 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                avPlayer.seek(to: .zero)
                avPlayer.play()
                avPlayer.rate = Float(defaults.object(forKey: "sany.ios.speed") as? Double ?? 1.0)
            }
        }
        let shouldAutoPlay = defaults.object(forKey: "sany.ios.autoPlay") as? Bool ?? true
        present(player, animated: true) {
            if shouldAutoPlay {
                avPlayer.play()
                avPlayer.rate = Float(defaults.object(forKey: "sany.ios.speed") as? Double ?? 1.0)
            }
        }
    }

    func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let channel = visible[indexPath.row]
        let key = channel.streamURL.absoluteString
        let isFavorite = favorites.contains(key)
        let action = UIContextualAction(style: .normal, title: isFavorite ? "Remove" : "Favorite") { [weak self] _, _, done in
            guard let self = self else { done(false); return }
            if self.favorites.contains(key) { self.favorites.remove(key) } else { self.favorites.insert(key) }
            UserDefaults.standard.set(Array(self.favorites), forKey: "sany.ios.favorites")
            self.applyFilters()
            done(true)
        }
        action.backgroundColor = SANYStyle.accent
        return UISwipeActionsConfiguration(actions: [action])
    }

    func tableView(_ tableView: UITableView, accessoryButtonTappedForRowWith indexPath: IndexPath) {
        let key = visible[indexPath.row].streamURL.absoluteString
        if favorites.contains(key) { favorites.remove(key) } else { favorites.insert(key) }
        UserDefaults.standard.set(Array(favorites), forKey: "sany.ios.favorites")
        applyFilters()
    }
}

private final class CheckerViewController: BaseViewController, UITableViewDataSource {
    private let status = UILabel()
    private let progress = UIProgressView(progressViewStyle: .default)
    private let startButton = UIButton(type: .system)
    private let offlineButton = UIButton(type: .system)
    private let filter = UISegmentedControl(items: ["All", "Working", "Offline", "Restricted", "Error"])
    private let resultsTable = UITableView(frame: .zero, style: .plain)
    private var running = false
    private var pendingChecks: [SANYChannel] = []
    private var onlineCount = 0
    private var offlineOnlyRun = false
    private var results: [[String: String]] = UserDefaults.standard.array(forKey: "sany.ios.checkerResults") as? [[String: String]] ?? []
    private var visibleResults: [[String: String]] {
        guard filter.selectedSegmentIndex > 0 else { return results }
        let key = ["", "working", "offline", "restricted", "error"][filter.selectedSegmentIndex]
        return results.filter { $0["status"] == key }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Channel Checker"
        tabBarItem = UITabBarItem(title: "Checker", image: nil, tag: 1)
        status.text = results.isEmpty ? "প্লেলিস্ট লোড করে চ্যানেল পরীক্ষা করুন" : "সংরক্ষিত ফলাফল: \(results.count)টি"
        status.textColor = .white; status.textAlignment = .center; status.numberOfLines = 0
        progress.progressTintColor = SANYStyle.accent
        startButton.setTitle("সব চ্যানেল পরীক্ষা করুন", for: .normal)
        startButton.setTitleColor(.white, for: .normal)
        startButton.backgroundColor = UIColor(red: 0.04, green: 0.38, blue: 0.52, alpha: 1)
        startButton.layer.cornerRadius = 12
        startButton.addTarget(self, action: #selector(startCheck), for: .touchUpInside)
        offlineButton.setTitle("শুধু Offline / Error পুনরায় পরীক্ষা", for: .normal)
        offlineButton.setTitleColor(SANYStyle.accent, for: .normal)
        offlineButton.addTarget(self, action: #selector(recheckOffline), for: .touchUpInside)
        filter.selectedSegmentIndex = 0
        filter.addTarget(self, action: #selector(filterChanged), for: .valueChanged)
        resultsTable.backgroundColor = SANYStyle.background
        resultsTable.dataSource = self
        resultsTable.register(UITableViewCell.self, forCellReuseIdentifier: "checkerResult")
        resultsTable.rowHeight = 58
        let stack = UIStackView(arrangedSubviews: [status, progress, startButton, offlineButton, filter, resultsTable])
        stack.axis = .vertical; stack.spacing = 10; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8),
            startButton.heightAnchor.constraint(equalToConstant: 48),
            offlineButton.heightAnchor.constraint(equalToConstant: 42)
        ])
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Clear", style: .plain, target: self, action: #selector(clearResults))
        resultsTable.reloadData()
    }

    @objc private func filterChanged() { resultsTable.reloadData() }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { visibleResults.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "checkerResult", for: indexPath)
        let item = visibleResults[indexPath.row]
        let state = item["status"] ?? "error"
        cell.backgroundColor = SANYStyle.panel
        cell.textLabel?.numberOfLines = 2
        cell.textLabel?.font = .systemFont(ofSize: 12)
        cell.textLabel?.text = "● \(state.uppercased())  \(item["name"] ?? "Unknown")\n\(item["detail"] ?? "") • \(item["date"] ?? "")"
        cell.textLabel?.textColor = state == "working" ? UIColor(red: 0.2, green: 0.85, blue: 0.4, alpha: 1) : (state == "restricted" ? UIColor(red: 1, green: 0.65, blue: 0.15, alpha: 1) : UIColor(red: 1, green: 0.3, blue: 0.3, alpha: 1))
        return cell
    }

    @objc private func clearResults() {
        results.removeAll()
        UserDefaults.standard.removeObject(forKey: "sany.ios.checkerResults")
        resultsTable.reloadData()
        status.text = "Checker ফলাফল মুছে ফেলা হয়েছে"
    }

    @objc private func startCheck() {
        guard !running else { return }
        running = true; offlineOnlyRun = false
        startButton.isEnabled = false; offlineButton.isEnabled = false
        status.text = "Playlist লোড হচ্ছে…"
        PlaylistStore.shared.load { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .failure(let error): self.finish("Playlist load failed: \(error.localizedDescription)")
            case .success(let channels):
                self.results.removeAll()
                self.pendingChecks = channels
                self.onlineCount = 0
                self.progress.progress = 0
                self.checkNext(index: 0)
            }
        }
    }

    @objc private func recheckOffline() {
        guard !running else { return }
        PlaylistStore.shared.load { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .failure(let error): self.status.text = "Playlist load failed: \(error.localizedDescription)"
            case .success(let channels):
                let bad = Set(self.results.filter { ["offline", "error", "restricted"].contains($0["status"] ?? "") }.compactMap { $0["url"] })
                self.pendingChecks = channels.filter { bad.contains($0.streamURL.absoluteString) }
                guard !self.pendingChecks.isEmpty else { self.status.text = "Offline/Error চ্যানেল পাওয়া যায়নি।"; return }
                self.results.removeAll { bad.contains($0["url"] ?? "") }
                self.running = true; self.offlineOnlyRun = true; self.onlineCount = 0
                self.startButton.isEnabled = false; self.offlineButton.isEnabled = false
                self.progress.progress = 0
                self.checkNext(index: 0)
            }
        }
    }

    private func checkNext(index: Int) {
        if index >= pendingChecks.count {
            UserDefaults.standard.set(ISO8601DateFormatter().string(from: Date()), forKey: "sany.ios.checkerLastRun")
            UserDefaults.standard.set(results, forKey: "sany.ios.checkerResults")
            let working = results.filter { $0["status"] == "working" }.count
            let offline = results.filter { $0["status"] == "offline" }.count
            let restricted = results.filter { $0["status"] == "restricted" }.count
            let errors = results.filter { $0["status"] == "error" }.count
            finish("\(offlineOnlyRun ? "Offline পুনরায় পরীক্ষা" : "সম্পূর্ণ পরীক্ষা") শেষ। মোট \(results.count) • Working \(working) • Offline \(offline) • Restricted \(restricted) • Error \(errors)")
            return
        }
        let channel = pendingChecks[index]
        var request = URLRequest(url: channel.streamURL)
        request.httpMethod = "GET"
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        request.timeoutInterval = 8
        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let state: String
            let detail: String
            if let error = error { state = "error"; detail = error.localizedDescription }
            else if (200...399).contains(code) { state = "working"; detail = "HTTP \(code)" }
            else if code == 401 || code == 403 { state = "restricted"; detail = "HTTP \(code)" }
            else if code == 404 || code == 410 { state = "offline"; detail = "HTTP \(code)" }
            else { state = "error"; detail = "HTTP \(code)" }
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.results.append(["name": channel.name, "url": channel.streamURL.absoluteString, "status": state, "detail": detail, "date": ISO8601DateFormatter().string(from: Date())])
                if state == "working" { self.onlineCount += 1 }
                self.progress.progress = Float(index + 1) / Float(max(self.pendingChecks.count, 1))
                self.status.text = "Checking \(index + 1)/\(self.pendingChecks.count) • \(Int(self.progress.progress * 100))% • Working \(self.onlineCount)"
                if (index + 1) % 10 == 0 {
                    UserDefaults.standard.set(self.results, forKey: "sany.ios.checkerResults")
                    self.resultsTable.reloadData()
                }
                self.checkNext(index: index + 1)
            }
        }.resume()
    }

    private func finish(_ message: String) {
        running = false; startButton.isEnabled = true; offlineButton.isEnabled = true
        UserDefaults.standard.set(results, forKey: "sany.ios.checkerResults")
        status.text = message
        resultsTable.reloadData()
    }
}

private final class PlaybackSettingsViewController: BaseViewController, UITableViewDataSource, UITableViewDelegate, UIDocumentPickerDelegate {
    private let table = UITableView(frame: .zero, style: .grouped)
    private let sections = [
        "APPEARANCE", "PLAYBACK SETTINGS", "PLAYER & PLAYLIST",
        "APP UPDATES", "DEVICE & ADMIN", "ADDITIONAL SETTINGS", "ABOUT"
    ]
    private let appearanceRows = ["Customize accent color", "Low internet mode", "Reduce UI animations", "App theme"]
    private let playbackRows = ["Playback speed", "Auto Play", "Video Quality", "Hardware Decoder", "Buffer Control", "Auto Retry on Failure", "Background video playback", "Picture-in-Picture"]
    private let playlistRows = ["Playlist manager", "Add playlist URL", "Import M3U file", "Playlist Checker", "Reload playlist", "Permanent SANY playlist"]
    private let updateRows = ["Check for updates"]
    private let adminRows = ["Copy Device ID (Admin)", "Maintenance status"]
    private let epgRows = ["Open EPG Guide", "Update EPG data", "Auto add EPG data", "EPG sources (3)"]
    private let aboutRows = ["SANY IPTV • iPhone Edition"]
    private var rows: [[String]] { [appearanceRows, playbackRows, playlistRows, updateRows, adminRows, epgRows, aboutRows] }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Settings"
        tabBarItem = UITabBarItem(title: "Settings", image: nil, tag: 2)
        table.backgroundColor = SANYStyle.background
        table.dataSource = self
        table.delegate = self
        table.register(UITableViewCell.self, forCellReuseIdentifier: "setting")
        table.separatorColor = UIColor.white.withAlphaComponent(0.08)
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)
        NSLayoutConstraint.activate([
            table.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    func numberOfSections(in tableView: UITableView) -> Int { sections.count }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { rows[section].count }
    func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? { sections[section] }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "setting", for: indexPath)
        let title = rows[indexPath.section][indexPath.row]
        cell.backgroundColor = SANYStyle.panel
        cell.textLabel?.textColor = .white
        cell.detailTextLabel?.textColor = SANYStyle.secondary
        cell.textLabel?.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        cell.accessoryView = nil
        cell.accessoryType = .disclosureIndicator
        cell.selectionStyle = .default
        cell.textLabel?.text = title

        if let toggle = toggleDefinition(section: indexPath.section, row: indexPath.row) {
            let control = UISwitch()
            control.onTintColor = SANYStyle.accent
            control.isOn = UserDefaults.standard.object(forKey: toggle.key) as? Bool ?? toggle.defaultValue
            control.addTarget(self, action: #selector(toggleChanged(_:)), for: .valueChanged)
            control.accessibilityIdentifier = toggle.key
            cell.accessoryView = control
            cell.accessoryType = .none
            cell.selectionStyle = .none
        } else {
            cell.detailTextLabel?.text = detailFor(title)
            cell.accessoryType = title == "SANY IPTV • iPhone Edition" ? .none : .disclosureIndicator
        }
        return cell
    }

    private func toggleDefinition(section: Int, row: Int) -> (key: String, defaultValue: Bool)? {
        if section == 0 {
            switch row {
            case 1: return ("sany.ios.lowDataMode", true)
            case 2: return ("sany.ios.reduceMotion", false)
            default: return nil
            }
        }
        if section == 1 {
            switch row {
            case 1: return ("sany.ios.autoPlay", true)
            case 5: return ("sany.ios.autoRetry", true)
            case 6: return ("sany.ios.backgroundPlayback", true)
            case 7: return ("sany.ios.pictureInPicture", true)
            default: return nil
            }
        }
        if section == 5 && row == 1 { return ("sany.ios.autoAddEPG", true) }
        return nil
    }

    @objc private func toggleChanged(_ sender: UISwitch) {
        guard let key = sender.accessibilityIdentifier else { return }
        UserDefaults.standard.set(sender.isOn, forKey: key)
        if key == "sany.ios.reduceMotion" { UIView.setAnimationsEnabled(!sender.isOn) }
        if key == "sany.ios.backgroundPlayback" && sender.isOn {
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
                try AVAudioSession.sharedInstance().setActive(true)
            } catch { showMessage("Background audio session চালু করা যায়নি: \(error.localizedDescription)") }
        }
        if key == "sany.ios.hardwareDecoder" {
            showMessage("iOS AVPlayer hardware/software decoder নির্বাচন করার API দেয় না। তাই সেটিংসটি সংরক্ষিত থাকে, কিন্তু iOS নিজেই decoder নির্বাচন করে।")
        }
        if key == "sany.ios.autoAddEPG" {
            showMessage("Auto add EPG চালু: M3U playlist-এর url-tvg / x-tvg-url পাওয়া গেলে সেটি সংরক্ষণ করা হবে এবং পরবর্তী EPG update-এ যুক্ত হবে।")
        }
    }

    private func detailFor(_ title: String) -> String? {
        let d = UserDefaults.standard
        switch title {
        case "Customize accent color": return d.string(forKey: "sany.ios.accent") ?? "Blue"
        case "App theme": return d.string(forKey: "sany.ios.theme") ?? "Night"
        case "Playback speed": return String(format: "%.2fx", d.object(forKey: "sany.ios.speed") as? Double ?? 1.0)
        case "Video Quality": return d.string(forKey: "sany.ios.quality") ?? "Auto"
        case "Buffer Control": return d.string(forKey: "sany.ios.buffer") ?? "Balanced"
        case "Hardware Decoder": return "iOS selects decoder automatically"
        case "Background video playback": return "iOS background audio; PiP is separate"
        case "Playlist manager": return "Saved playlists: \(savedPlaylists().count)"
        case "Permanent SANY playlist": return "Owner playlist • locked"
        case "EPG sources (3)": return "3 built-in XMLTV feeds"
        case "Open EPG Guide": return "Browse downloaded programme schedule"
        case "SANY IPTV • iPhone Edition": return "M3U / M3U8 player"
        default: return nil
        }
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let title = rows[indexPath.section][indexPath.row]
        switch title {
        case "Customize accent color":
            choose(title: "Accent color", options: ["Blue", "Teal", "Purple", "Orange"], key: "sany.ios.accent")
        case "App theme":
            choose(title: "App theme", options: ["Day", "Night", "System default"], key: "sany.ios.theme") { [weak self] value in
                SANYStyle.applyTheme(value)
                self?.table.reloadData()
            }
        case "Hardware Decoder":
            showMessage("Android ExoPlayer-এর মতো hardware/software decoder বেছে নেওয়ার public API iOS AVPlayer দেয় না। iOS নিরাপদ ও সমর্থিত decoder নিজে নির্বাচন করে; তাই ভুয়া ON/OFF সুইচ দেখানো হচ্ছে না।")
        case "Playback speed":
            choose(title: "Playback speed", options: ["0.75x", "1.0x", "1.25x", "1.5x", "2.0x"], key: "sany.ios.speed", values: ["0.75", "1.0", "1.25", "1.5", "2.0"])
        case "Video Quality":
            choose(title: "Video Quality", options: ["Auto", "480p", "720p", "1080p"], key: "sany.ios.quality")
        case "Buffer Control":
            choose(title: "Buffer Control", options: ["Low — faster start", "Balanced — recommended", "High — fewer interruptions"], key: "sany.ios.buffer", values: ["Low", "Balanced", "High"])
        case "Playlist manager": showPlaylistManager()
        case "Add playlist URL": addPlaylist()
        case "Import M3U file": presentDocumentPicker()
        case "Playlist Checker": tabBarController?.selectedIndex = 1
        case "Reload playlist": reloadActivePlaylist()
        case "Permanent SANY playlist": loadPlaylist("https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u", locked: true)
        case "Check for updates": checkForUpdates()
        case "Copy Device ID (Admin)": copyDeviceID()
        case "Maintenance status": checkMaintenance()
        case "Open EPG Guide": navigationController?.pushViewController(EPGGuideViewController(), animated: true)
        case "Update EPG data": updateEPG()
        case "EPG sources (3)": showEPGSources()
        case "SANY IPTV • iPhone Edition":
            showMessage("SANY IPTV • iPhone Edition\nM3U / M3U8 player\nAndroid app source remains unchanged.")
        default: break
        }
    }

    private func choose(title: String, options: [String], key: String, values: [String]? = nil, completion: ((String) -> Void)? = nil) {
        let alert = UIAlertController(title: title, message: nil, preferredStyle: .actionSheet)
        for (index, option) in options.enumerated() {
            alert.addAction(UIAlertAction(title: option, style: .default) { [weak self] _ in
                let value = values?[index] ?? option
                if key == "sany.ios.speed", let speed = Double(value) {
                    UserDefaults.standard.set(speed, forKey: key)
                } else {
                    UserDefaults.standard.set(value, forKey: key)
                }
                completion?(value)
                self?.navigationController?.navigationBar.tintColor = SANYStyle.accent
                self?.tabBarController?.tabBar.tintColor = SANYStyle.accent
                self?.table.reloadData()
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        if let popover = alert.popoverPresentationController {
            popover.sourceView = table
            popover.sourceRect = CGRect(x: table.bounds.midX, y: table.bounds.midY, width: 1, height: 1)
        }
        present(alert, animated: true)
    }

    private func savedPlaylists() -> [String] {
        let permanent = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u"
        let saved = UserDefaults.standard.stringArray(forKey: "sany.ios.playlists") ?? []
        return [permanent] + saved.filter { $0 != permanent }
    }

    private func showPlaylistManager() {
        let items = savedPlaylists()
        let labels = items.enumerated().map { index, value in index == 0 ? "★ SANY IPTV — PERMANENT / LOCKED" : "\(index). \(value)" }
        let alert = UIAlertController(title: "Playlist manager", message: "Select a saved playlist to load. The built-in SANY playlist cannot be removed.", preferredStyle: .actionSheet)
        for (index, label) in labels.enumerated() {
            alert.addAction(UIAlertAction(title: label, style: .default) { [weak self] _ in self?.loadPlaylist(items[index], locked: index == 0) })
        }
        alert.addAction(UIAlertAction(title: "＋ Add playlist URL", style: .default) { [weak self] _ in self?.addPlaylist() })
        alert.addAction(UIAlertAction(title: "Import M3U file", style: .default) { [weak self] _ in self?.presentDocumentPicker() })
        if items.count > 1 {
            alert.addAction(UIAlertAction(title: "Remove a saved playlist", style: .destructive) { [weak self] _ in self?.removePlaylist() })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        if let popover = alert.popoverPresentationController { popover.sourceView = table; popover.sourceRect = CGRect(x: table.bounds.midX, y: table.bounds.midY, width: 1, height: 1) }
        present(alert, animated: true)
    }

    private func addPlaylist() {
        let alert = UIAlertController(title: "Add playlist URL", message: "Enter an M3U or M3U8 HTTP/HTTPS URL.", preferredStyle: .alert)
        alert.addTextField { field in field.placeholder = "https://example.com/playlist.m3u"; field.keyboardType = .URL; field.autocapitalizationType = .none }
        alert.addAction(UIAlertAction(title: "Add & Load", style: .default) { [weak self, weak alert] _ in
            guard let self = self, let value = alert?.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines), value.hasPrefix("http") else { self?.showMessage("সঠিক HTTP/HTTPS URL দিন"); return }
            var saved = UserDefaults.standard.stringArray(forKey: "sany.ios.playlists") ?? []
            if !saved.contains(value) { saved.append(value) }
            UserDefaults.standard.set(saved, forKey: "sany.ios.playlists")
            self.loadPlaylist(value, locked: false)
        })
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func removePlaylist() {
        let saved = savedPlaylists()
        let removable = Array(saved.dropFirst())
        let alert = UIAlertController(title: "Remove playlist", message: nil, preferredStyle: .actionSheet)
        for value in removable {
            alert.addAction(UIAlertAction(title: value, style: .destructive) { [weak self] _ in
                var current = UserDefaults.standard.stringArray(forKey: "sany.ios.playlists") ?? []
                current.removeAll { $0 == value }
                UserDefaults.standard.set(current, forKey: "sany.ios.playlists")
                if PlaylistStore.shared.activePlaylist == value {
                    let fallback = "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u"
                    UserDefaults.standard.set(fallback, forKey: "sany.ios.activePlaylist")
                    PlaylistStore.shared.load(from: fallback, forceRefresh: true) { _ in }
                }
                self?.table.reloadData()
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        if let popover = alert.popoverPresentationController { popover.sourceView = table; popover.sourceRect = CGRect(x: table.bounds.midX, y: table.bounds.midY, width: 1, height: 1) }
        present(alert, animated: true)
    }

    private func loadPlaylist(_ value: String, locked: Bool) {
        showMessage("Playlist loading শুরু হয়েছে। লোড সম্পন্ন হলে Live TV ট্যাবে চ্যানেলগুলো দেখা যাবে।")
        PlaylistStore.shared.load(from: value, forceRefresh: true) { [weak self] result in
            switch result {
            case .success(let channels): self?.showMessage("Playlist loaded: \(channels.count) channels\n\(PlaylistStore.shared.loadedFromCache ? "Offline cache used." : "Online refresh complete.")")
            case .failure(let error): self?.showMessage("Playlist load failed: \(error.localizedDescription)")
            }
            self?.table.reloadData()
        }
    }

    private func reloadActivePlaylist() {
        PlaylistStore.shared.load(from: PlaylistStore.shared.activePlaylist, forceRefresh: true) { [weak self] result in
            switch result {
            case .success(let channels): self?.showMessage("Playlist reloaded: \(channels.count) channels\n\(PlaylistStore.shared.loadedFromCache ? "Offline cache used." : "Online refresh complete.")")
            case .failure(let error): self?.showMessage("Reload failed: \(error.localizedDescription)")
            }
        }
    }

    private func presentDocumentPicker() {
        let picker = UIDocumentPickerViewController(documentTypes: ["public.text", "public.data"], in: .import)
        picker.delegate = self
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        let granted = url.startAccessingSecurityScopedResource()
        defer { if granted { url.stopAccessingSecurityScopedResource() } }
        do {
            let text = try String(contentsOf: url, encoding: .utf8)
            PlaylistStore.shared.importPlaylist(text: text, name: url.lastPathComponent) { [weak self] result in
                switch result {
                case .success(let channels): self?.showMessage("Imported \(channels.count) channels")
                case .failure(let error): self?.showMessage(error.localizedDescription)
                }
                self?.table.reloadData()
            }
        } catch let readError as NSError { showMessage("ফাইল পড়া যায়নি: \(readError.localizedDescription)") }
    }

    private func checkForUpdates() {
        // App Store lookup is the source of truth for public iOS updates.
        guard let url = URL(string: "https://itunes.apple.com/lookup?bundleId=com.sany.iptv.ios&entity=software") else { return }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        URLSession.shared.dataTask(with: request) { data, _, error in
            DispatchQueue.main.async {
                if let error = error { self.showMessage("App Store update check failed: \(error.localizedDescription)"); return }
                guard let data = data,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let count = json["resultCount"] as? Int, count > 0,
                      let item = (json["results"] as? [[String: Any]])?.first,
                      let storeVersion = item["version"] as? String,
                      let storeURLString = item["trackViewUrl"] as? String,
                      let storeURL = URL(string: storeURLString) else {
                    self.showMessage("SANY IPTV iOS App Store listing এখনও পাওয়া যায়নি। App Store-এ প্রকাশিত হলে এখান থেকে ভার্সন চেক ও Store update করা যাবে। Unsigned IPA দিয়ে App Store update করা যাবে না.")
                    return
                }
                let current = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
                if self.compareVersion(storeVersion, isNewerThan: current) {
                    let alert = UIAlertController(title: "Update available", message: "Installed: \(current)\nApp Store: \(storeVersion)\nনতুন সংস্করণটি App Store থেকে ইনস্টল করুন।", preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "Open App Store", style: .default) { _ in UIApplication.shared.open(storeURL) })
                    alert.addAction(UIAlertAction(title: "Later", style: .cancel))
                    self.present(alert, animated: true)
                } else {
                    self.showMessage("আপনার SANY IPTV সর্বশেষ App Store সংস্করণে আছে। Version: \(current)")
                }
            }
        }.resume()
    }

    private func compareVersion(_ candidate: String, isNewerThan current: String) -> Bool {
        let lhs = candidate.split(separator: ".").compactMap { Int($0) }
        let rhs = current.split(separator: ".").compactMap { Int($0) }
        for index in 0..<max(lhs.count, rhs.count) {
            let a = index < lhs.count ? lhs[index] : 0
            let b = index < rhs.count ? rhs[index] : 0
            if a != b { return a > b }
        }
        return false
    }

    private func copyDeviceID() {
        let key = "sany.ios.installationID"
        let id: String
        if let existing = UserDefaults.standard.string(forKey: key) { id = existing }
        else {
            id = UUID().uuidString.lowercased()
            UserDefaults.standard.set(id, forKey: key)
        }
        UIPasteboard.general.string = id
        showMessage("এই iPhone-এর Installation ID কপি হয়েছে। Maintenance ON অবস্থায় iPhone-কে অনুমতি দিতে maintenance.json-এর allowed_ios_devices তালিকায় এই ID যোগ করুন। Android-এর allowed_devices তালিকা অপরিবর্তিত থাকবে।")
    }

    private func checkMaintenance() {
        guard let url = URL(string: "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/maintenance.json") else { return }
        URLSession.shared.dataTask(with: url) { data, _, error in
            DispatchQueue.main.async {
                if let error = error { self.showMessage("Maintenance status check failed: \(error.localizedDescription)"); return }
                guard let data = data,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { self.showMessage("Maintenance configuration পাওয়া যায়নি।"); return }
                let enabled = json["enabled"] as? Bool ?? false
                let message = json["message"] as? String ?? "No maintenance message."
                self.showMessage(enabled ? "Maintenance ON\n\(message)\nAdmin-device matching এখনও iOS-এ যুক্ত হয়নি।" : "Maintenance OFF — app access is open.")
            }
        }.resume()
    }

    private let epgSources = [
        "http://epg.it999.ru/epg.xml.gz",
        "https://worker-9dd4.onrender.com/guide.xml.gz",
        "https://raw.githubusercontent.com/StrangeDrVN/epg/public/guide.xml.gz"
    ]

    private func showEPGSources() {
        let last = UserDefaults.standard.string(forKey: "sany.ios.epgLastUpdated") ?? "Never"
        showMessage(epgSources.enumerated().map { "Source \( $0.offset + 1): \( $0.element)" }.joined(separator: "\n\n") + "\n\nLast successful update: \(last)\niOS background refresh is best-effort; iOS controls when it runs.")
    }

    private func updateEPG() {
        _ = EPGRepository.update { result in
            switch result {
            case .success(let summary): self.showMessage(summary)
            case .failure(let error): self.showMessage("EPG update failed: \(error.localizedDescription)")
            }
        }
    }}

private struct EPGProgramme {
    let channel: String
    let title: String
    let start: Date?
    let stop: Date?
}

private final class EPGGuideViewController: UIViewController, UITableViewDataSource, XMLParserDelegate, UISearchBarDelegate {
    private let table = UITableView(frame: .zero, style: .plain)
    private let searchBar = UISearchBar()
    private let timeFilter = UISegmentedControl(items: ["All", "Now", "Upcoming"])
    private var programmes: [EPGProgramme] = []
    private var channelNames: [String: String] = [:]
    private var currentChannelID = ""
    private var currentChannel = ""
    private var currentTitle = ""
    private var currentStart: Date?
    private var currentStop: Date?
    private var captureTitle = false
    private var captureDisplayName = false
    private var displayNameText = ""
    private var searchText = ""

    private var visibleProgrammes: [EPGProgramme] {
        let now = Date()
        return programmes.filter { item in
            let matchesText = searchText.isEmpty || item.channel.localizedCaseInsensitiveContains(searchText) || item.title.localizedCaseInsensitiveContains(searchText)
            let matchesTime: Bool
            switch timeFilter.selectedSegmentIndex {
            case 1: matchesTime = (item.start ?? .distantFuture) <= now && (item.stop ?? .distantPast) > now
            case 2: matchesTime = (item.start ?? .distantPast) > now
            default: matchesTime = true
            }
            return matchesText && matchesTime
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "EPG Guide"
        view.backgroundColor = SANYStyle.background
        table.backgroundColor = SANYStyle.background
        table.dataSource = self
        table.rowHeight = UITableView.automaticDimension
        table.estimatedRowHeight = 64
        table.register(UITableViewCell.self, forCellReuseIdentifier: "programme")
        searchBar.delegate = self
        searchBar.placeholder = "Search channel or programme"
        searchBar.barStyle = .black
        timeFilter.selectedSegmentIndex = 0
        timeFilter.addTarget(self, action: #selector(filtersChanged), for: .valueChanged)
        searchBar.translatesAutoresizingMaskIntoConstraints = false
        timeFilter.translatesAutoresizingMaskIntoConstraints = false
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(searchBar)
        view.addSubview(timeFilter)
        view.addSubview(table)
        NSLayoutConstraint.activate([
            searchBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            searchBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            searchBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            timeFilter.topAnchor.constraint(equalTo: searchBar.bottomAnchor),
            timeFilter.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            timeFilter.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            table.topAnchor.constraint(equalTo: timeFilter.bottomAnchor, constant: 6),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        loadGuide()
    }

    private func loadGuide() {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let file = folder.appendingPathComponent("epg_data.xml")
        guard FileManager.default.fileExists(atPath: file.path) else {
            showGuideError("EPG ডেটা পাওয়া যায়নি। Settings → Update EPG data চালান।")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            guard let parser = XMLParser(contentsOf: file) else {
                DispatchQueue.main.async { self.showGuideError("EPG XML ফাইল খোলা যায়নি।") }
                return
            }
            parser.delegate = self
            parser.shouldResolveExternalEntities = false
            let ok = parser.parse()
            DispatchQueue.main.async {
                if ok {
                    self.programmes.sort { ($0.start ?? .distantFuture) < ($1.start ?? .distantFuture) }
                    self.table.reloadData()
                    self.navigationItem.prompt = "\(self.programmes.count) programmes"
                    if self.programmes.isEmpty { self.showGuideError("EPG programme পাওয়া যায়নি।") }
                } else {
                    self.showGuideError(parser.parserError?.localizedDescription ?? "EPG XML parse failed")
                }
            }
        }
    }

    private func showGuideError(_ message: String) {
        let label = UILabel()
        label.text = message
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 0
        label.frame = view.bounds.insetBy(dx: 28, dy: 28)
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        table.backgroundView = label
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        if elementName == "channel" {
            currentChannelID = attributeDict["id"] ?? ""
        } else if elementName == "display-name" {
            captureDisplayName = true
            displayNameText = ""
        } else if elementName == "programme" {
            let identifier = attributeDict["channel"] ?? ""
            currentChannel = channelNames[identifier] ?? identifier
            currentStart = parseXMLTVDate(attributeDict["start"])
            currentStop = parseXMLTVDate(attributeDict["stop"])
            currentTitle = ""
        } else if elementName == "title" {
            captureTitle = true
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if captureTitle { currentTitle += string }
        if captureDisplayName { displayNameText += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "title" { captureTitle = false }
        if elementName == "display-name" {
            captureDisplayName = false
            if !currentChannelID.isEmpty && channelNames[currentChannelID] == nil {
                channelNames[currentChannelID] = displayNameText.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        if elementName == "programme" {
            let name = currentTitle.trimmingCharacters(in: .whitespacesAndNewlines)
            if !name.isEmpty {
                programmes.append(EPGProgramme(channel: currentChannel, title: name, start: currentStart, stop: currentStop))
            }
        }
    }

    private func parseXMLTVDate(_ value: String?) -> Date? {
        guard let value = value else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMddHHmmss Z"
        return formatter.date(from: String(value.prefix(20)))
    }

    @objc private func filtersChanged() { table.reloadData() }
    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) {
        self.searchText = searchText
        table.reloadData()
    }
    func searchBarSearchButtonClicked(_ searchBar: UISearchBar) { searchBar.resignFirstResponder() }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { visibleProgrammes.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "programme", for: indexPath)
        let item = visibleProgrammes[indexPath.row]
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.timeZone = .current
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        let period = item.start.map { formatter.string(from: $0) } ?? "Time unavailable"
        cell.backgroundColor = SANYStyle.panel
        cell.textLabel?.textColor = .white
        cell.textLabel?.numberOfLines = 0
        cell.textLabel?.text = "\(period) • \(item.channel)\n\(item.title)"
        cell.textLabel?.font = .systemFont(ofSize: 13, weight: .medium)
        return cell
    }
}

private final class EPGUpdateOperation {
    private let lock = NSLock()
    private var tasks: [URLSessionDataTask] = []
    func add(_ task: URLSessionDataTask) { lock.lock(); tasks.append(task); lock.unlock() }
    func cancel() { lock.lock(); let current = tasks; lock.unlock(); current.forEach { $0.cancel() } }
}

private enum EPGRepository {
    static let sources = [
        "http://epg.it999.ru/epg.xml.gz",
        "https://worker-9dd4.onrender.com/guide.xml.gz",
        "https://raw.githubusercontent.com/StrangeDrVN/epg/public/guide.xml.gz"
    ]

    static func detectPlaylistEPG(_ playlist: String) {
        guard UserDefaults.standard.object(forKey: "sany.ios.autoAddEPG") as? Bool ?? true else { return }
        let pattern = #"(?i)(?:url-tvg|x-tvg-url|tvg-url)=[\"']([^\"']+)[\"']"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: playlist, range: NSRange(playlist.startIndex..., in: playlist)),
              let range = Range(match.range(at: 1), in: playlist) else { return }
        let value = String(playlist[range])
        guard let url = URL(string: value), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return }
        UserDefaults.standard.set(value, forKey: "sany.ios.epgURL")
    }

    static func update(completion: @escaping (Result<String, Error>) -> Void) -> EPGUpdateOperation {
        let operation = EPGUpdateOperation()
        var allSources = sources
        if let custom = UserDefaults.standard.string(forKey: "sany.ios.epgURL"), !custom.isEmpty, !allSources.contains(custom) { allSources.append(custom) }
        let group = DispatchGroup()
        let lock = NSLock()
        var bodies: [Int: String] = [:]
        var failures: [String] = []
        for (index, source) in allSources.enumerated() {
            guard let url = URL(string: source) else { failures.append("Source \(index + 1): invalid URL"); continue }
            group.enter()
            var request = URLRequest(url: url)
            request.timeoutInterval = 75
            request.setValue("SANY-IPTV-iOS", forHTTPHeaderField: "User-Agent")
            request.setValue("application/gzip, application/xml, text/xml, */*", forHTTPHeaderField: "Accept")
            let task = URLSession.shared.dataTask(with: request) { data, response, error in
                defer { group.leave() }
                do {
                    if let error = error { throw error }
                    guard let data = data, let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw NSError(domain: "SANYEPG", code: 1, userInfo: [NSLocalizedDescriptionKey: "HTTP response was not successful"]) }
                    let decoded = try decode(data)
                    guard let xml = String(data: decoded, encoding: .utf8) else { throw NSError(domain: "SANYEPG", code: 2, userInfo: [NSLocalizedDescriptionKey: "Feed is not UTF-8 XML"]) }
                    let parser = XMLParser(data: decoded)
                    parser.shouldResolveExternalEntities = false
                    guard parser.parse() else { throw parser.parserError ?? NSError(domain: "SANYEPG", code: 3, userInfo: [NSLocalizedDescriptionKey: "Invalid XMLTV document"]) }
                    let openRegex = try NSRegularExpression(pattern: #"(?is)<tv\b[^>]*>"#)
                    let closeRegex = try NSRegularExpression(pattern: #"(?is)</tv\s*>"#)
                    let ns = NSRange(xml.startIndex..., in: xml)
                    guard let openMatch = openRegex.firstMatch(in: xml, range: ns),
                          let openRange = Range(openMatch.range, in: xml),
                          let closeMatch = closeRegex.firstMatch(in: xml, range: NSRange(openRange.upperBound..., in: xml)),
                          let closeRange = Range(closeMatch.range, in: xml) else { throw NSError(domain: "SANYEPG", code: 4, userInfo: [NSLocalizedDescriptionKey: "XMLTV <tv> root not found"]) }
                    let body = String(xml[openRange.upperBound..<closeRange.lowerBound])
                    lock.lock(); bodies[index] = body; lock.unlock()
                } catch {
                    lock.lock(); failures.append("Source \(index + 1): \(error.localizedDescription)"); lock.unlock()
                }
            }
            operation.add(task)
            task.resume()
        }
        group.notify(queue: .global(qos: .utility)) {
            lock.lock(); let ordered = bodies.keys.sorted().compactMap { bodies[$0] }; let errors = failures; lock.unlock()
            guard let first = ordered.first else {
                let error = NSError(domain: "SANYEPG", code: 5, userInfo: [NSLocalizedDescriptionKey: errors.joined(separator: " | ").isEmpty ? "All EPG sources failed" : errors.joined(separator: " | ")])
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }
            do {
                let xmlHeader = #"<?xml version="1.0" encoding="UTF-8"?>"#
                let merged = xmlHeader + "\n<tv>" + ordered.joined(separator: "\n") + "</tv>"
                let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let destination = folder.appendingPathComponent("epg_data.xml")
                try Data(merged.utf8).write(to: destination, options: .atomic)
                let stamp = ISO8601DateFormatter().string(from: Date())
                UserDefaults.standard.set(stamp, forKey: "sany.ios.epgLastUpdated")
                let summary = "EPG updated: \(ordered.count)/\(allSources.count) feeds downloaded and merged.\nSaved: \(destination.lastPathComponent)" + (errors.isEmpty ? "" : "\nSome feeds failed: \(errors.joined(separator: "; "))")
                DispatchQueue.main.async { completion(.success(summary)) }
            } catch { DispatchQueue.main.async { completion(.failure(error)) } }
        }
        return operation
    }

    private static func decode(_ data: Data) throws -> Data {
        guard data.count >= 2, data[data.startIndex] == 0x1f, data[data.startIndex + 1] == 0x8b else { return data }
        var stream = z_stream()
        let status = inflateInit2_(&stream, 15 + 32, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard status == Z_OK else { throw NSError(domain: "SANYEPG", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "Could not initialize gzip decoder"]) }
        defer { inflateEnd(&stream) }
        return try data.withUnsafeBytes { raw -> Data in
            guard let base = raw.bindMemory(to: Bytef.self).baseAddress else { throw NSError(domain: "SANYEPG", code: 6, userInfo: [NSLocalizedDescriptionKey: "Empty gzip feed"]) }
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: base)
            stream.avail_in = uInt(data.count)
            var output = Data()
            while true {
                var chunk = [UInt8](repeating: 0, count: 65536)
                let result = chunk.withUnsafeMutableBufferPointer { buffer -> Int32 in
                    stream.next_out = buffer.baseAddress
                    stream.avail_out = uInt(buffer.count)
                    return inflate(&stream, Z_NO_FLUSH)
                }
                let produced = 65536 - Int(stream.avail_out)
                if produced > 0 { output.append(contentsOf: chunk.prefix(produced)) }
                if output.count > 512 * 1024 * 1024 { throw NSError(domain: "SANYEPG", code: 7, userInfo: [NSLocalizedDescriptionKey: "EPG feed exceeds 512 MB limit"]) }
                if result == Z_STREAM_END { break }
                if result != Z_OK { throw NSError(domain: "SANYEPG", code: Int(result), userInfo: [NSLocalizedDescriptionKey: "Gzip feed could not be decompressed"]) }
                if stream.avail_in == 0 && produced == 0 { throw NSError(domain: "SANYEPG", code: 8, userInfo: [NSLocalizedDescriptionKey: "Gzip feed ended unexpectedly"]) }
            }
            return output
        }
    }
}
