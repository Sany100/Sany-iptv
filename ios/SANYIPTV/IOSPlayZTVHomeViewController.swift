import UIKit
import AVKit

private struct IOSPlayZTVItem {
    let title: String
    let links: [URL]
}

final class IOSPlayZTVHomeViewController: UITableViewController {
    private var categories: [String] = []
    private var loading = false
    private var errorText: String?

    init() {
        super.init(style: .plain)
        title = "PlayZTV Live"
        tabBarItem = UITabBarItem(title: "Live Sports", image: IOSIcon.image("sportscourt"), selectedImage: IOSIcon.image("sportscourt.fill"))
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = IOSAppStyle.background
        tableView.backgroundColor = IOSAppStyle.background
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "PlayZCategory")
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Refresh", style: .plain, target: self, action: #selector(refresh))
        load()
    }

    @objc private func refresh() { load() }

    private func load() {
        guard !loading else { return }
        loading = true
        errorText = nil
        tableView.reloadData()
        IOSPlayZTVProvider.shared.loadCategories { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.loading = false
                switch result {
                case .success(let values): self.categories = values
                case .failure(let error): self.errorText = error.localizedDescription
                }
                self.tableView.reloadData()
            }
        }
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        categories.isEmpty ? 1 : categories.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "PlayZCategory", for: indexPath)
        cell.backgroundColor = IOSAppStyle.card
        cell.textLabel?.textColor = IOSAppStyle.primaryText
        cell.textLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        if categories.isEmpty {
            cell.textLabel?.text = loading ? "Loading PlayZTV categories…" : (errorText ?? "No PlayZTV categories found. Tap Refresh.")
            cell.textLabel?.textColor = IOSAppStyle.secondaryText
            cell.accessoryType = .none
        } else {
            cell.textLabel?.text = categories[indexPath.row]
            cell.accessoryType = .disclosureIndicator
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard categories.indices.contains(indexPath.row) else { return }
        let category = categories[indexPath.row]
        let detail = IOSPlayZTVCategoryViewController(categoryPath: category)
        navigationController?.pushViewController(detail, animated: true)
    }
}

private final class IOSPlayZTVCategoryViewController: UITableViewController {
    private let categoryPath: String
    private var items: [IOSPlayZTVItem] = []
    private var loading = true
    private var errorText: String?

    init(categoryPath: String) {
        self.categoryPath = categoryPath
        super.init(style: .plain)
        title = URL(string: categoryPath)?.lastPathComponent ?? categoryPath
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = IOSAppStyle.background
        tableView.backgroundColor = IOSAppStyle.background
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "PlayZChannel")
        IOSPlayZTVProvider.shared.loadCategoryPayload(path: categoryPath) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.loading = false
                switch result {
                case .success(let payload): self.items = Self.extractItems(payload)
                case .failure(let error): self.errorText = error.localizedDescription
                }
                self.tableView.reloadData()
            }
        }
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        items.isEmpty ? 1 : items.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "PlayZChannel", for: indexPath)
        cell.backgroundColor = IOSAppStyle.card
        cell.textLabel?.textColor = IOSAppStyle.primaryText
        if items.isEmpty {
            cell.textLabel?.text = loading ? "Loading channels…" : (errorText ?? "No playable channels were returned for this category.")
            cell.textLabel?.textColor = IOSAppStyle.secondaryText
            cell.accessoryType = .none
        } else {
            cell.textLabel?.text = items[indexPath.row].title
            cell.accessoryType = .disclosureIndicator
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard items.indices.contains(indexPath.row) else { return }
        let item = items[indexPath.row]
        guard let url = item.links.first else { return }
        if item.links.count > 1 {
            let alert = UIAlertController(title: item.title, message: "Choose a stream source", preferredStyle: .actionSheet)
            for (index, link) in item.links.enumerated() {
                alert.addAction(UIAlertAction(title: "Source \(index + 1)", style: .default) { [weak self] _ in self?.play(link, title: item.title) })
            }
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            if let popover = alert.popoverPresentationController { popover.sourceView = tableView; popover.sourceRect = tableView.rectForRow(at: indexPath) }
            present(alert, animated: true)
        } else { play(url, title: item.title) }
    }

    private func play(_ url: URL, title: String) {
        let player = AVPlayerViewController()
        player.player = AVPlayer(url: url)
        player.title = title
        present(player, animated: true) { player.player?.play() }
    }

    private static func extractItems(_ payload: String) -> [IOSPlayZTVItem] {
        guard let data = payload.data(using: .utf8) else { return [] }
        if let text = String(data: data, encoding: .utf8), text.hasPrefix("#EXTM3U") {
            return parseM3U(text)
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) else { return [] }
        var dictionaries = [[String: Any]]()
        func walk(_ value: Any) {
            if let array = value as? [Any] { array.forEach(walk); return }
            if let dict = value as? [String: Any] {
                dictionaries.append(dict)
                dict.values.forEach(walk)
            }
        }
        walk(object)
        return dictionaries.compactMap { dict in
            guard let title = (dict["name"] as? String) ?? (dict["title"] as? String) else { return nil }
            let raw = (dict["links"] as? String) ?? (dict["link"] as? String) ?? (dict["url"] as? String) ?? ""
            let urls = raw.split(separator: ",").compactMap { URL(string: String($0).trimmingCharacters(in: .whitespacesAndNewlines)) }
                .filter { ["http", "https"].contains($0.scheme?.lowercased() ?? "") }
            guard !urls.isEmpty else { return nil }
            return IOSPlayZTVItem(title: title, links: urls)
        }
    }

    private static func parseM3U(_ text: String) -> [IOSPlayZTVItem] {
        var result = [IOSPlayZTVItem]()
        var title: String?
        for line in text.components(separatedBy: .newlines).map({ $0.trimmingCharacters(in: .whitespacesAndNewlines) }) {
            if line.hasPrefix("#EXTINF:") {
                title = line.components(separatedBy: ",").last?.trimmingCharacters(in: .whitespaces)
            } else if !line.isEmpty, !line.hasPrefix("#"), let name = title, let url = URL(string: line), ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
                result.append(IOSPlayZTVItem(title: name, links: [url]))
                title = nil
            }
        }
        return result
    }
}
