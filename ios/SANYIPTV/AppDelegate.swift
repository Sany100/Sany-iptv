import UIKit
import AVKit

private struct SANYChannel {
    let name: String
    let streamURL: URL
    let group: String
    let logoURL: URL?
}

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

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
        return true
    }
}

private enum SANYStyle {
    static let background = UIColor(red: 0.018, green: 0.035, blue: 0.085, alpha: 1)
    static let panel = UIColor(red: 0.045, green: 0.075, blue: 0.14, alpha: 1)
    static let accent = UIColor(red: 0.1, green: 0.85, blue: 0.98, alpha: 1)
    static let secondary = UIColor(red: 0.62, green: 0.7, blue: 0.82, alpha: 1)
}

private final class PlaylistStore {
    static let shared = PlaylistStore()
    private(set) var channels: [SANYChannel] = []
    private var loading = false
    private var callbacks: [(Result<[SANYChannel], Error>) -> Void] = []
    private let playlistURL = URL(string: "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u")!

    func load(completion: @escaping (Result<[SANYChannel], Error>) -> Void) {
        if loading {
            callbacks.append(completion)
            return
        }
        if !channels.isEmpty {
            completion(.success(channels))
            return
        }
        loading = true
        callbacks.append(completion)
        var request = URLRequest(url: playlistURL)
        request.timeoutInterval = 30
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            let result: Result<[SANYChannel], Error>
            if let error = error {
                result = .failure(error)
            } else if let data = data,
                      let http = response as? HTTPURLResponse,
                      (200...299).contains(http.statusCode),
                      let text = String(data: data, encoding: .utf8) {
                let parsed = self.parse(text)
                if parsed.isEmpty {
                    result = .failure(NSError(domain: "SANYPlaylist", code: 1, userInfo: [NSLocalizedDescriptionKey: "প্লেলিস্টে কোনো চ্যানেল পাওয়া যায়নি।"]))
                } else {
                    self.channels = parsed
                    result = .success(parsed)
                }
            } else {
                result = .failure(NSError(domain: "SANYPlaylist", code: 2, userInfo: [NSLocalizedDescriptionKey: "প্লেলিস্টের উত্তর সঠিক নয়। পরে আবার চেষ্টা করুন।"]))
            }
            DispatchQueue.main.async {
                self.loading = false
                let pending = self.callbacks
                self.callbacks.removeAll()
                pending.forEach { $0(result) }
            }
        }.resume()
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
                        if let keyRange = Range(match.range(at: 1), in: line),
                           let valueRange = Range(match.range(at: 2), in: line) {
                            attrs[String(line[keyRange]).lowercased()] = String(line[valueRange])
                        }
                    }
                }
                group = attrs["group-title"].flatMap { $0.isEmpty ? nil : $0 } ?? "Other"
                logo = attrs["tvg-logo"].flatMap { URL(string: $0) }
                name = line.components(separatedBy: ",").last?.trimmingCharacters(in: .whitespaces) ?? "Unknown channel"
            } else if !line.isEmpty && !line.hasPrefix("#"), let url = URL(string: line), let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme) {
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

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "SANY IPTV"
        tabBarItem = UITabBarItem(title: "Live TV", image: UIImage(systemName: "tv"), tag: 0)
        searchBar.placeholder = "চ্যানেল খুঁজুন"
        searchBar.delegate = self
        searchBar.barStyle = .black
        searchBar.searchBarStyle = .minimal
        searchBar.tintColor = SANYStyle.accent
        filter.selectedSegmentIndex = 0
        filter.addTarget(self, action: #selector(filterChanged), for: .valueChanged)
        filter.backgroundColor = SANYStyle.panel
        filter.selectedSegmentTintColor = UIColor(red: 0.05, green: 0.35, blue: 0.48, alpha: 1)
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
        let player = AVPlayerViewController()
        player.player = AVPlayer(url: channel.streamURL)
        player.title = channel.name
        present(player, animated: true) { player.player?.play() }
    }

    func tableView(_ tableView: UITableView, accessoryButtonTappedForRowWith indexPath: IndexPath) {
        let key = visible[indexPath.row].streamURL.absoluteString
        if favorites.contains(key) { favorites.remove(key) } else { favorites.insert(key) }
        UserDefaults.standard.set(Array(favorites), forKey: "sany.ios.favorites")
        applyFilters()
    }
}

private final class CheckerViewController: BaseViewController {
    private let status = UILabel()
    private let progress = UIProgressView(progressViewStyle: .default)
    private let startButton = UIButton(type: .system)
    private var checks: [(SANYChannel, Bool)] = []
    private var running = false

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Channel Checker"
        tabBarItem = UITabBarItem(title: "Checker", image: UIImage(systemName: "checkmark.seal"), tag: 1)
        status.text = "প্লেলিস্ট লোড করে চ্যানেল পরীক্ষা করুন"
        status.textColor = .white
        status.textAlignment = .center
        status.numberOfLines = 0
        progress.progressTintColor = SANYStyle.accent
        startButton.setTitle("চেক শুরু করুন", for: .normal)
        startButton.setTitleColor(.white, for: .normal)
        startButton.backgroundColor = UIColor(red: 0.04, green: 0.38, blue: 0.52, alpha: 1)
        startButton.layer.cornerRadius = 12
        startButton.addTarget(self, action: #selector(startCheck), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [status, progress, startButton])
        stack.axis = .vertical
        stack.spacing = 22
        stack.alignment = .fill
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            startButton.heightAnchor.constraint(equalToConstant: 52)
        ])
    }

    @objc private func startCheck() {
        guard !running else { return }
        running = true
        startButton.isEnabled = false
        startButton.alpha = 0.55
        status.text = "প্লেলিস্ট লোড হচ্ছে…"
        PlaylistStore.shared.load { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .failure(let error):
                self.finish("লোড ব্যর্থ: \(error.localizedDescription)")
            case .success(let channels):
                self.checks = []
                self.progress.progress = 0
                self.checkNext(channels, index: 0, online: 0)
            }
        }
    }

    private func checkNext(_ channels: [SANYChannel], index: Int, online: Int) {
        if index >= channels.count {
            let offline = channels.count - online
            finish("চেক সম্পন্ন: \(channels.count)টি চ্যানেলের মধ্যে \(online)টি response দিয়েছে, \(offline)টি response দেয়নি।")
            return
        }
        let channel = channels[index]
        var request = URLRequest(url: channel.streamURL)
        request.httpMethod = "GET"
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        request.timeoutInterval = 8
        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            let ok = error == nil && (response as? HTTPURLResponse).map { (200...399).contains($0.statusCode) } == true
            DispatchQueue.main.async {
                guard let self = self else { return }
                let count = online + (ok ? 1 : 0)
                self.progress.progress = Float(index + 1) / Float(max(channels.count, 1))
                self.status.text = "Checking: \(index + 1)/\(channels.count)  •  \(Int(self.progress.progress * 100))%\nOnline response: \(count)"
                self.checkNext(channels, index: index + 1, online: count)
            }
        }.resume()
    }

    private func finish(_ message: String) {
        running = false
        startButton.isEnabled = true
        startButton.alpha = 1
        status.text = message
    }
}

private final class PlaybackSettingsViewController: BaseViewController, UITableViewDataSource, UITableViewDelegate {
    private let rows = ["Auto Play", "Hardware Decoder", "Auto Retry on Failure"]
    private let keys = ["sany.ios.autoplay", "sany.ios.hardwareDecoder", "sany.ios.autoRetry"]
    private let qualityKey = "sany.ios.quality"
    private let qualities = ["Auto", "480p", "720p", "1080p"]
    private let table = UITableView(frame: .zero, style: .insetGrouped)

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Playback Settings"
        tabBarItem = UITabBarItem(title: "Settings", image: UIImage(systemName: "gearshape"), tag: 2)
        table.backgroundColor = SANYStyle.background
        table.dataSource = self
        table.delegate = self
        table.register(UITableViewCell.self, forCellReuseIdentifier: "setting")
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)
        NSLayoutConstraint.activate([
            table.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    func numberOfSections(in tableView: UITableView) -> Int { 2 }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? rows.count : 1 }

    func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == 0 ? "Playback Controls" : "Video Quality"
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "setting", for: indexPath)
        cell.backgroundColor = SANYStyle.panel
        cell.textLabel?.textColor = .white
        if indexPath.section == 0 {
            let key = keys[indexPath.row]
            cell.textLabel?.text = rows[indexPath.row]
            let toggle = UISwitch()
            toggle.onTintColor = SANYStyle.accent
            toggle.isOn = UserDefaults.standard.object(forKey: key) as? Bool ?? (indexPath.row != 1)
            toggle.tag = indexPath.row
            toggle.addTarget(self, action: #selector(toggleChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
            cell.selectionStyle = .none
        } else {
            let selected = UserDefaults.standard.string(forKey: qualityKey) ?? "Auto"
            cell.textLabel?.text = "Video Quality: \(selected)"
            cell.accessoryType = .disclosureIndicator
            cell.selectionStyle = .default
        }
        return cell
    }

    @objc private func toggleChanged(_ sender: UISwitch) {
        UserDefaults.standard.set(sender.isOn, forKey: keys[sender.tag])
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath.section == 1 else { return }
        let alert = UIAlertController(title: "Video Quality", message: "পছন্দের quality নির্বাচন করুন", preferredStyle: .actionSheet)
        for quality in qualities {
            alert.addAction(UIAlertAction(title: quality, style: .default) { [weak self] _ in
                UserDefaults.standard.set(quality, forKey: self?.qualityKey ?? "sany.ios.quality")
                self?.table.reloadData()
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        if let popover = alert.popoverPresentationController {
            popover.sourceView = tableView.cellForRow(at: indexPath)
            popover.sourceRect = tableView.cellForRow(at: indexPath)?.bounds ?? .zero
        }
        present(alert, animated: true)
    }
}
