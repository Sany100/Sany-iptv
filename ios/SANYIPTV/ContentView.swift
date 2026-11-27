import UIKit
import AVKit

private struct TVChannel {
    let name: String
    let url: String
    let group: String
    let logo: String?
}

final class ChannelListViewController: UITableViewController, UISearchBarDelegate {
    private let playlistURL = URL(string: "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u")!
    private var channels: [TVChannel] = []
    private var filtered: [TVChannel] = []
    private var favorites = Set<String>(UserDefaults.standard.stringArray(forKey: "sany.ios.favorites") ?? [])
    private var selectedGroup = "All"
    private var groups: [String] { return ["All", "Favorites"] + Array(Set(channels.map { $0.group })).sorted() }
    private let searchBar = UISearchBar()
    private let groupControl = UISegmentedControl(items: ["All", "Favorites"])
    private let refresh = UIRefreshControl()

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "SANY IPTV"
        view.backgroundColor = UIColor(red: 0.015, green: 0.035, blue: 0.09, alpha: 1)
        tableView.backgroundColor = view.backgroundColor
        tableView.separatorColor = UIColor.white.withAlphaComponent(0.10)
        tableView.rowHeight = 68
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "Channel")
        navigationController?.navigationBar.barTintColor = UIColor(red: 0.02, green: 0.07, blue: 0.15, alpha: 1)
        navigationController?.navigationBar.titleTextAttributes = [.foregroundColor: UIColor.cyan, .font: UIFont.boldSystemFont(ofSize: 20)]
        navigationController?.navigationBar.tintColor = .cyan

        searchBar.placeholder = "Search channels"
        searchBar.delegate = self
        searchBar.barStyle = .black
        searchBar.searchBarStyle = .minimal
        searchBar.tintColor = .cyan
        tableView.tableHeaderView = searchBar

        refresh.tintColor = .cyan
        refresh.addTarget(self, action: #selector(loadPlaylist), for: .valueChanged)
        tableView.refreshControl = refresh

        navigationItem.leftBarButtonItem = UIBarButtonItem(title: "Categories", style: .plain, target: self, action: #selector(showGroups))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Refresh", style: .plain, target: self, action: #selector(loadPlaylist))
        loadPlaylist()
    }

    @objc private func loadPlaylist() {
        refresh.beginRefreshing()
        var request = URLRequest(url: playlistURL)
        request.timeoutInterval = 30
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.refresh.endRefreshing()
                if let error = error {
                    self.showMessage("Playlist could not load: " + error.localizedDescription)
                    return
                }
                guard let data = data, let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                      let text = String(data: data, encoding: .utf8) else {
                    self.showMessage("Playlist response was invalid. Please try again.")
                    return
                }
                let parsed = self.parseM3U(text)
                guard !parsed.isEmpty else {
                    self.showMessage("No channels found in the playlist.")
                    return
                }
                self.channels = parsed
                self.applyFilters()
            }
        }.resume()
    }

    private func parseM3U(_ text: String) -> [TVChannel] {
        var output: [TVChannel] = []
        var name = ""
        var group = "Other"
        var logo: String?
        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.lowercased().hasPrefix("#extinf") {
                let pattern = #"([\w-]+)="([^"]*)""#
                if let regex = try? NSRegularExpression(pattern: pattern) {
                    let range = NSRange(line.startIndex..<line.endIndex, in: line)
                    var attrs: [String: String] = [:]
                    for match in regex.matches(in: line, range: range) where match.numberOfRanges == 3 {
                        if let kr = Range(match.range(at: 1), in: line), let vr = Range(match.range(at: 2), in: line) {
                            attrs[String(line[kr]).lowercased()] = String(line[vr])
                        }
                    }
                    group = attrs["group-title"].flatMap { $0.isEmpty ? nil : $0 } ?? "Other"
                    logo = attrs["tvg-logo"]
                }
                name = line.components(separatedBy: ",").last?.trimmingCharacters(in: .whitespaces) ?? "Unknown channel"
            } else if !line.isEmpty && !line.hasPrefix("#") {
                let fallback = line.split(separator: "/").last.map(String.init) ?? "Channel \(output.count + 1)"
                output.append(TVChannel(name: name.isEmpty ? fallback : name, url: line, group: group, logo: logo))
                name = ""
                group = "Other"
                logo = nil
            }
        }
        var seen = Set<String>()
        return output.filter { seen.insert($0.url).inserted }
    }

    @objc private func showGroups() {
        let alert = UIAlertController(title: "Live TV categories", message: nil, preferredStyle: .actionSheet)
        for group in groups {
            alert.addAction(UIAlertAction(title: group == selectedGroup ? "✓ " + group : group, style: .default) { _ in
                self.selectedGroup = group
                self.applyFilters()
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func applyFilters() {
        let query = searchBar.text ?? ""
        filtered = channels.filter { channel in
            let groupMatch = selectedGroup == "All" || (selectedGroup == "Favorites" ? favorites.contains(channel.url) : channel.group == selectedGroup)
            let searchMatch = query.isEmpty || channel.name.localizedCaseInsensitiveContains(query) || channel.group.localizedCaseInsensitiveContains(query)
            return groupMatch && searchMatch
        }
        tableView.reloadData()
    }

    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) { applyFilters() }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { return filtered.count }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let channel = filtered[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: "Channel", for: indexPath)
        cell.backgroundColor = UIColor.white.withAlphaComponent(0.045)
        cell.textLabel?.text = channel.name + "\n" + channel.group
        cell.textLabel?.numberOfLines = 2
        cell.textLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        cell.textLabel?.textColor = .white
        cell.detailTextLabel?.textColor = .lightGray
        cell.accessoryType = favorites.contains(channel.url) ? .detailButton : .disclosureIndicator
        cell.tintColor = .cyan
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let channel = filtered[indexPath.row]
        guard let url = URL(string: channel.url) else { return }
        let player = AVPlayerViewController()
        player.player = AVPlayer(url: url)
        present(player, animated: true) { player.player?.play() }
    }

    override func tableView(_ tableView: UITableView, accessoryButtonTappedForRowWith indexPath: IndexPath) {
        toggleFavorite(filtered[indexPath.row])
    }

    private func toggleFavorite(_ channel: TVChannel) {
        if favorites.contains(channel.url) { favorites.remove(channel.url) } else { favorites.insert(channel.url) }
        UserDefaults.standard.set(Array(favorites), forKey: "sany.ios.favorites")
        applyFilters()
    }

    private func showMessage(_ message: String) {
        let alert = UIAlertController(title: "SANY IPTV", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
}
