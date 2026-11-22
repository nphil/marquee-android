import Foundation
import MarqueeCore

/// Where Home's rows come from and what they are called (DESIGN §8.1), as the iPhone app's `HomeRows` does it.
enum HomeRows {
    /// The Home hubs, then the hubs of the movie and TV libraries in `libraries` (every one when nil),
    /// in the server's order. No library is asked when `libraries` is empty, so a hidden row costs no
    /// request. A library that does not answer leaves its rows out rather than failing Home. Libraries in
    /// `hidden` (switched off under Favorite Libraries) give no rows, and their titles leave the Home hubs.
    static func load(from library: PlexLibrary, libraries: Set<String>?, hidden: Set<String> = []) async throws -> [HomeRow] {
        async let promoted = library.promotedHubs()
        var sections: [PlexLibrarySection] = []
        var hiddenKeys: Set<Int> = []
        if libraries?.isEmpty != true || !hidden.isEmpty {
            let all = try await library.sections()
            hiddenKeys = Set(all.filter { hidden.contains(HomeLayout.libraryID($0)) }.compactMap { Int($0.key) })
            if libraries?.isEmpty != true {
                sections = all.filter { section in
                    (section.type == .movie || section.type == .show)
                        && !hidden.contains(HomeLayout.libraryID(section))
                        && libraries.map { $0.contains(HomeLayout.libraryID(section)) } ?? true
                }
            }
        }
        let hubs = await withTaskGroup(of: (Int, [PlexHub]).self) { group in
            for (index, section) in sections.enumerated() {
                group.addTask { (index, (try? await library.recommendedHubs(in: section.key)) ?? []) }
            }
            var hubs = [[PlexHub]](repeating: [], count: sections.count)
            for await (index, answer) in group { hubs[index] = answer }
            return hubs
        }
        let home = try await promoted.compactMap { hub -> PlexHub? in
            guard !hiddenKeys.isEmpty else { return hub }
            var hub = hub
            hub.items.removeAll { $0.librarySectionID.map(hiddenKeys.contains) ?? false }
            return hub.items.isEmpty ? nil : hub
        }
        return rows(home, library: nil)
            + zip(sections, hubs).flatMap { section, hubs in rows(hubs, library: section) }
    }

    /// `rows` with Continue Watching following the shows watched again (`RewatchQueue`): its cards come
    /// from `run`, and the row is made when Plex lists none. Nil when `run` takes longer than `limit`.
    static func following(_ rows: [HomeRow], through run: RewatchRun, dismissed: RewatchDismissals, within limit: Duration? = nil) async -> [HomeRow]? {
        let at = rows.firstIndex { $0.isContinueWatching }
        let plex = at.map { rows[$0].hub.items } ?? []
        guard let cards = await run.continuing(plex, dismissed: dismissed, within: limit) else { return nil }
        guard cards != plex else { return rows }
        var rows = rows
        if let at {
            rows[at].hub.items = cards
        } else {
            rows.insert(HomeRow(hub: PlexHub(identifier: "home.continue", title: "Continue Watching", style: "hero", items: cards)), at: 0)
        }
        return rows
    }

    static func isContinueWatching(_ hub: PlexHub) -> Bool {
        hub.style == "hero" || hub.identifier == "home.continue"
    }

    /// Continue Watching and the hubs that hold films or TV, without playlists among their items.
    private static func rows(_ hubs: [PlexHub], library: PlexLibrarySection?) -> [HomeRow] {
        hubs.compactMap { hub in
            let continuing = library == nil && isContinueWatching(hub)
            guard continuing || hub.type.map(videoHubTypes.contains) ?? true else { return nil }
            var hub = hub
            hub.items.removeAll { $0.type == .playlist || $0.type == .unknown }
            return HomeRow(hub: hub, library: library)
        }
    }

    private static let videoHubTypes: Set<String> = ["movie", "show", "season", "episode", "mixed", "clip"]
}

extension HomeRow {
    var isContinueWatching: Bool {
        library == nil && HomeRows.isContinueWatching(hub)
    }

    /// A library's hub carries its library's name, as Plex Web words it: "Top Rated in Movies".
    var displayTitle: String {
        if isContinueWatching, hub.title.isEmpty { return "Continue Watching" }
        guard let library else { return hub.title }
        return "\(hub.title) in \(library.title)"
    }
}
