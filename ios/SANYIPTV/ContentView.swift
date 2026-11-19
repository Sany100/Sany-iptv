import SwiftUI
import AVKit

private struct TVChannel: Identifiable, Hashable {
    let name: String
    let url: String
    let group: String
    let logo: String?

    var id: String { url }
}

struct ContentView: View {
    private let playlistURL = URL(string: "https://raw.githubusercontent.com/Sany100/Sany-iptv/main/playlist.m3u")!

    @State private var channels: [TVChannel] = []
    @State private var selectedGroup = "All"
    @State private var searchText = ""
    @State private var favorites: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "sany.ios.favorites") ?? [])
    @State private var selectedChannel: TVChannel?
    @State private var isLoading = false
    @State private var errorMessage: String?

    private var groups: [String] {
        ["All", "Favorites"] + Array(Set(channels.map(\.group))).sorted()
    }

    private var visibleChannels: [TVChannel] {
        channels.filter { channel in
            let matchesGroup = selectedGroup == "All" ||
                (selectedGroup == "Favorites" ? favorites.contains(channel.id) : channel.group == selectedGroup)
            let matchesSearch = searchText.isEmpty ||
                channel.name.localizedCaseInsensitiveContains(searchText) ||
                channel.group.localizedCaseInsensitiveContains(searchText)
            return matchesGroup && matchesSearch
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                LinearGradient(colors: [Color(red: 0.015, green: 0.035, blue: 0.09), Color(red: 0.025, green: 0.08, blue: 0.17), .black], startPoint: .topLeading, endPoint: .bottomTrailing)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header

                        HStack(spacing: 10) {
                            Image(systemName: "magnifyingglass")
                                .foregroundStyle(.cyan.opacity(0.8))
                            TextField("Search channels", text: $searchText)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .foregroundStyle(.white)
                        }
                        .padding(14)
                        .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.12), lineWidth: 1))

                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 9) {
                                ForEach(groups, id: \.self) { group in
                                    Button {
                                        selectedGroup = group
                                    } label: {
                                        Text(group)
                                            .font(.system(size: 13, weight: .semibold))
                                            .foregroundStyle(selectedGroup == group ? Color.black : Color.white.opacity(0.82))
                                            .padding(.horizontal, 15)
                                            .padding(.vertical, 10)
                                            .background(selectedGroup == group ? Color.cyan : Color.white.opacity(0.08), in: Capsule())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }

                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(selectedGroup == "All" ? "Live TV" : selectedGroup)
                                    .font(.title2.bold())
                                    .foregroundStyle(.white)
                                Text("\(visibleChannels.count) channels")
                                    .font(.caption)
                                    .foregroundStyle(.white.opacity(0.55))
                            }
                            Spacer()
                            Button {
                                Task { await loadPlaylist() }
                            } label: {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundStyle(.cyan)
                                    .padding(11)
                                    .background(.cyan.opacity(0.12), in: Circle())
                            }
                            .accessibilityLabel("Refresh playlist")
                        }

                        if isLoading && channels.isEmpty {
                            ProgressView("Loading SANY IPTV…")
                                .tint(.cyan)
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 45)
                        } else if let errorMessage, channels.isEmpty {
                            ContentUnavailableView {
                                Label("Could not load playlist", systemImage: "wifi.exclamationmark")
                            } description: {
                                Text(errorMessage)
                            } actions: {
                                Button("Try Again") { Task { await loadPlaylist() } }
                                    .buttonStyle(.borderedProminent)
                                    .tint(.cyan)
                            }
                        } else if visibleChannels.isEmpty {
                            ContentUnavailableView("No channels found", systemImage: "tv", description: Text("Try another category or search term."))
                                .foregroundStyle(.white)
                        } else {
                            LazyVStack(spacing: 10) {
                                ForEach(visibleChannels) { channel in
                                    channelRow(channel)
                                }
                            }
                        }

                        Text("SANY IPTV • PREMIUM IPTV EXPERIENCE")
                            .font(.system(size: 9, weight: .semibold, design: .rounded))
                            .tracking(1.4)
                            .foregroundStyle(.white.opacity(0.3))
                            .frame(maxWidth: .infinity)
                            .padding(.top, 12)
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 14)
                    .padding(.bottom, 28)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .task {
                if channels.isEmpty { await loadPlaylist() }
            }
            .fullScreenCover(item: $selectedChannel) { channel in
                PlayerScreen(channel: channel)
            }
        }
        .tint(.cyan)
    }

    private var header: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 14)
                    .fill(LinearGradient(colors: [.cyan, Color.blue.opacity(0.65)], startPoint: .topLeading, endPoint: .bottomTrailing))
                Image(systemName: "play.tv.fill")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.white)
            }
            .frame(width: 48, height: 48)
            .shadow(color: .cyan.opacity(0.28), radius: 12)

            VStack(alignment: .leading, spacing: 3) {
                Text("SANY IPTV")
                    .font(.system(size: 25, weight: .heavy, design: .rounded))
                    .tracking(1.1)
                    .foregroundStyle(.cyan)
                Text("PREMIUM IPTV EXPERIENCE")
                    .font(.system(size: 9, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(.white.opacity(0.66))
            }
            Spacer()
            Circle()
                .fill(channels.isEmpty ? Color.orange : Color.green)
                .frame(width: 8, height: 8)
                .accessibilityLabel(channels.isEmpty ? "Not connected" : "Connected")
        }
        .padding(16)
        .background(.ultraThinMaterial.opacity(0.45), in: RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(.white.opacity(0.13), lineWidth: 1))
    }

    private func channelRow(_ channel: TVChannel) -> some View {
        HStack(spacing: 13) {
            ZStack {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.cyan.opacity(0.10))
                if let logo = channel.logo, let url = URL(string: logo) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFit().padding(5)
                    } placeholder: {
                        Image(systemName: "tv.fill").foregroundStyle(.cyan)
                    }
                } else {
                    Image(systemName: "tv.fill").font(.system(size: 20)).foregroundStyle(.cyan)
                }
            }
            .frame(width: 48, height: 48)

            VStack(alignment: .leading, spacing: 4) {
                Text(channel.name)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(channel.group)
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.5))
                    .lineLimit(1)
            }
            Spacer()
            Button {
                toggleFavorite(channel)
            } label: {
                Image(systemName: favorites.contains(channel.id) ? "star.fill" : "star")
                    .foregroundStyle(favorites.contains(channel.id) ? .yellow : .white.opacity(0.55))
                    .frame(width: 36, height: 42)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(favorites.contains(channel.id) ? "Remove favorite" : "Add favorite")

            Image(systemName: "play.circle.fill")
                .font(.system(size: 25))
                .foregroundStyle(.cyan)
        }
        .padding(12)
        .background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 17))
        .overlay(RoundedRectangle(cornerRadius: 17).stroke(.white.opacity(0.09), lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture { selectedChannel = channel }
    }

    private func toggleFavorite(_ channel: TVChannel) {
        if favorites.contains(channel.id) {
            favorites.remove(channel.id)
        } else {
            favorites.insert(channel.id)
        }
        UserDefaults.standard.set(Array(favorites), forKey: "sany.ios.favorites")
    }

    @MainActor
    private func loadPlaylist() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            var request = URLRequest(url: playlistURL)
            request.timeoutInterval = 30
            request.setValue("SANY IPTV iOS", forHTTPHeaderField: "User-Agent")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw URLError(.badServerResponse)
            }
            guard let text = String(data: data, encoding: .utf8) else {
                throw URLError(.cannotDecodeContentData)
            }
            let parsed = parseM3U(text)
            guard !parsed.isEmpty else { throw URLError(.zeroByteResource) }
            channels = parsed
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func parseM3U(_ text: String) -> [TVChannel] {
        var result: [TVChannel] = []
        var name = ""
        var group = "Other"
        var logo: String?

        for rawLine in text.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.lowercased().hasPrefix("#extinf") {
                let pattern = #"([\w-]+)="([^"]*)""#
                if let regex = try? NSRegularExpression(pattern: pattern) {
                    let range = NSRange(line.startIndex..<line.endIndex, in: line)
                    var attributes: [String: String] = [:]
                    for match in regex.matches(in: line, range: range) where match.numberOfRanges == 3 {
                        if let keyRange = Range(match.range(at: 1), in: line),
                           let valueRange = Range(match.range(at: 2), in: line) {
                            attributes[String(line[keyRange]).lowercased()] = String(line[valueRange])
                        }
                    }
                    group = attributes["group-title"].flatMap { $0.isEmpty ? nil : $0 } ?? "Other"
                    logo = attributes["tvg-logo"]
                }
                name = line.components(separatedBy: ",").last?.trimmingCharacters(in: .whitespaces) ?? "Unknown channel"
                if name.isEmpty { name = "Unknown channel" }
            } else if !line.isEmpty && !line.hasPrefix("#") {
                let fallback = line.split(separator: "/").last.map(String.init) ?? "Channel \(result.count + 1)"
                result.append(TVChannel(name: name.isEmpty ? fallback : name, url: line, group: group, logo: logo))
                name = ""
                group = "Other"
                logo = nil
            }
        }
        var seen = Set<String>()
        return result.filter { seen.insert($0.url).inserted }
    }
}

private struct PlayerScreen: View {
    let channel: TVChannel
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            if let player {
                VideoPlayer(player: player)
                    .ignoresSafeArea()
            } else {
                ProgressView("Connecting to \(channel.name)…")
                    .tint(.cyan)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Button {
                player?.pause()
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(13)
                    .background(.black.opacity(0.55), in: Circle())
            }
            .padding(.top, 12)
            .padding(.leading, 16)
        }
        .onAppear {
            guard let url = URL(string: channel.url) else { return }
            let newPlayer = AVPlayer(url: url)
            player = newPlayer
            newPlayer.play()
        }
        .onDisappear {
            player?.pause()
            player = nil
        }
    }
}
