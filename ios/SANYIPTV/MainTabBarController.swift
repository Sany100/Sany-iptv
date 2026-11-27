import UIKit

final class SANYMainTabBarController: UITabBarController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.015, green: 0.035, blue: 0.09, alpha: 1)

        let liveTV = UINavigationController(rootViewController: ChannelListViewController())
        liveTV.tabBarItem = UITabBarItem(title: "Live TV", image: UIImage(systemName: "tv"), selectedImage: UIImage(systemName: "tv.fill"))

        let sports = makeSection(title: "Live Sports", symbol: "sportscourt")
        let movies = makeSection(title: "Movies", symbol: "film")
        let anime = makeSection(title: "Anime", symbol: "sparkles")
        let settings = makeSection(title: "Settings", symbol: "gearshape")

        viewControllers = [liveTV, sports, movies, anime, settings]

        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(red: 0.02, green: 0.07, blue: 0.15, alpha: 1)
        appearance.stackedLayoutAppearance.normal.iconColor = UIColor.lightGray
        appearance.stackedLayoutAppearance.normal.titleTextAttributes = [.foregroundColor: UIColor.lightGray]
        appearance.stackedLayoutAppearance.selected.iconColor = .cyan
        appearance.stackedLayoutAppearance.selected.titleTextAttributes = [.foregroundColor: UIColor.cyan]
        tabBar.standardAppearance = appearance
        if #available(iOS 15.0, *) {
            tabBar.scrollEdgeAppearance = appearance
        }
        tabBar.tintColor = .cyan
    }

    private func makeSection(title: String, symbol: String) -> UINavigationController {
        let controller = IOSSectionPlaceholderViewController(sectionTitle: title)
        let navigation = UINavigationController(rootViewController: controller)
        navigation.tabBarItem = UITabBarItem(title: title, image: UIImage(systemName: symbol), selectedImage: UIImage(systemName: symbol + ".fill"))
        return navigation
    }
}

final class IOSSectionPlaceholderViewController: UIViewController {
    private let sectionTitle: String

    init(sectionTitle: String) {
        self.sectionTitle = sectionTitle
        super.init(nibName: nil, bundle: nil)
        title = sectionTitle
        tabBarItem.title = sectionTitle
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.015, green: 0.035, blue: 0.09, alpha: 1)
        navigationController?.navigationBar.barTintColor = UIColor(red: 0.02, green: 0.07, blue: 0.15, alpha: 1)
        navigationController?.navigationBar.titleTextAttributes = [.foregroundColor: UIColor.cyan, .font: UIFont.boldSystemFont(ofSize: 20)]
        navigationController?.navigationBar.tintColor = .cyan

        let message = UILabel()
        message.translatesAutoresizingMaskIntoConstraints = false
        message.text = "\(sectionTitle) iPhone version is being built"
        message.textColor = .white
        message.font = UIFont.systemFont(ofSize: 17, weight: .semibold)
        message.textAlignment = .center
        message.numberOfLines = 0
        view.addSubview(message)

        NSLayoutConstraint.activate([
            message.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            message.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            message.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])
    }
}
