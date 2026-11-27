import UIKit

final class SANYMainTabBarController: UITabBarController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = IOSAppStyle.background

        let liveTV = UINavigationController(rootViewController: ChannelListViewController())
        liveTV.tabBarItem = UITabBarItem(title: "Live TV", image: IOSIcon.image("tv"), selectedImage: IOSIcon.image("tv.fill"))

        let sports = UINavigationController(rootViewController: IOSCatalogViewController(section: .sports))
        let movies = UINavigationController(rootViewController: IOSCatalogViewController(section: .movies))
        let anime = UINavigationController(rootViewController: IOSCatalogViewController(section: .anime))
        let settings = UINavigationController(rootViewController: IOSSettingsViewController())

        viewControllers = [liveTV, sports, movies, anime, settings]

        tabBar.barTintColor = IOSAppStyle.card
        tabBar.tintColor = IOSAppStyle.accent
        tabBar.unselectedItemTintColor = IOSAppStyle.secondaryText
        if #available(iOS 13.0, *) {
            let appearance = UITabBarAppearance()
            appearance.configureWithOpaqueBackground()
            appearance.backgroundColor = IOSAppStyle.card
            appearance.stackedLayoutAppearance.normal.iconColor = IOSAppStyle.secondaryText
            appearance.stackedLayoutAppearance.normal.titleTextAttributes = [.foregroundColor: IOSAppStyle.secondaryText]
            appearance.stackedLayoutAppearance.selected.iconColor = IOSAppStyle.accent
            appearance.stackedLayoutAppearance.selected.titleTextAttributes = [.foregroundColor: IOSAppStyle.accent]
            tabBar.standardAppearance = appearance
            if #available(iOS 15.0, *) { tabBar.scrollEdgeAppearance = appearance }
        }
    }
}
