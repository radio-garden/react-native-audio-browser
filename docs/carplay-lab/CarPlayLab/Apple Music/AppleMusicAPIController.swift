/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
This file implements a simplified interface to the Apple Music API and manages authorization with StoreKit.
*/
import os.log
import Foundation
import StoreKit
import MediaPlayer
import CarPlay

/// `AppleMusicAPIController` interfaces with the Apple Music API.
class AppleMusicAPIController {
    
    /// Replace the placeholder below with the token you generated in step 5 of the sample code configuration.
    static let developerToken = """
        <Your Token Here>
        """
    
    /// The singleton AppleMusicAPIController handles all things related to the Apple Music API.
    static let sharedController = AppleMusicAPIController()
    /// The API authorization header for accessing the Apple Music API.
    static let authorizationHeader = "Bearer \(developerToken)"
    /// The base URL for accessing the Apple Music API.
    static let baseURL = "https://api.music.apple.com"
    /// The UserDefaults key for storing the user token.
    static let userTokenDefaultsKeyName = "userToken"
    /// Manages the settings value for useAppleMusic.
    var useAppleMusic: Bool = true
    /// Manages the settings value for allowExplicitContent.
    var allowExplicitContent: Bool = false
    /// Token storage for managing the user token.
    private var tokenStorage = TokenStorage()
    /// The local storefont. This should fetch the locale.
    private var storefront: String?
    /// The user token for creation of the JWT.
    private var userToken: String?
    /// The date decoder for formatting dates from the Apple Music API.
    private let dateDecoder = DateDecoder()
    /// The cloud service controller for StoreKit authorizations.
    private let cloudServiceController = SKCloudServiceController()

    private enum SearchType: String {
        case album = "albums"
        case artist = "artists"
        case song = "songs"
        case media = "albums,artists,songs"
    }
    
    /// The chart type to fetch.
    private enum ChartType: String {
        case song = "songs"
    }

    /// Retrieves a saved user token.
    private func retrieveSavedUserToken() -> String? {
        return tokenStorage.tokenText
    }

    /// Saves a user token.
    private func saveUserToken(_ userToken: String) throws {
            try tokenStorage.store(tokenText: userToken)
    }
    
    // MARK: Play
    
    /// Class function for playing an array of items.
    static func playWithItems(items: [String]) {
        CPNowPlayingTemplate.shared.isUpNextButtonEnabled = items.count > 1
        CPNowPlayingTemplate.shared.isAlbumArtistButtonEnabled = true
        MPMusicPlayerController.applicationQueuePlayer.pause()
        MPMusicPlayerController.applicationQueuePlayer.setQueue(with: items)
        MPMusicPlayerController.applicationQueuePlayer.prepareToPlay()
        MPMusicPlayerController.applicationQueuePlayer.play()
    }
    
    // MARK: API Preparation

    /// Use this method to get the user token and current storefront prior to accessing the API.
    func prepareForRequests(_ completion: @escaping (Bool) -> Void) {
        let continueAfterAuthorization: () -> Void = {
            self.fetchUserToken { fetchedUserToken in
                guard let userToken = fetchedUserToken else {
                    completion(false)
                    return
                }
                self.userToken = userToken
                // Save the user token to avoid having to fetch it from StoreKit again later.
                do {
                    try self.saveUserToken(userToken)
                    MemoryLogger.shared.appendEvent("Saved user token.")
                } catch {
                    MemoryLogger.shared.appendEvent("Failed to save user token.")
                }
                self.fetchStorefront { fetchedStorefront in
                    guard let storefront = fetchedStorefront else {
                        MemoryLogger.shared.appendEvent("Failed to fetch store front.")
                        completion(false)
                        return
                    }

                    self.storefront = storefront
                    MemoryLogger.shared.appendEvent("Fetched store front.")
                    completion(true)
                }
            }
        }
        if SKCloudServiceController.authorizationStatus() != .authorized {
            requestAuthorization { authorized in
                if !authorized {
                    completion(false)
                } else {
                    continueAfterAuthorization()
                }
            }
        } else {
            continueAfterAuthorization()
        }
    }
    
    /// Request authorization to access StoreKit subscriptions.
    private func requestAuthorization(_ completion: @escaping (Bool) -> Void) {
        SKCloudServiceController.requestAuthorization { authorizationStatus in
            switch authorizationStatus {
            case .authorized:
                completion(true)
            default:
                completion(false)
            }
        }
    }

    /// Fetch the current user token.
    private func fetchUserToken(_ completion: @escaping (String?) -> Void) {
        if let savedToken = retrieveSavedUserToken() {
            completion(savedToken)
        } else {
            cloudServiceController.requestUserToken(forDeveloperToken: AppleMusicAPIController.developerToken) { fetchedUserToken, error in
                guard let resolvedUserToken = fetchedUserToken else {
                    let errorString = error?.localizedDescription ?? "<unknown>"
                    os_log("Failed to fetch user token error: %{public}@", log: OSLog.default, type: .error, errorString)
                    completion(nil)
                    return
                }
                os_log("Fetched user token: %{public}@", log: OSLog.default, type: .info, resolvedUserToken)
                completion(fetchedUserToken)
            }
        }
    }

    /// Fetch the local store front.
    private func fetchStorefront(_ completion: @escaping (String?) -> Void) {
        cloudServiceController.requestStorefrontCountryCode { (code, error) in
            guard let code = code else {
                let errorString = error?.localizedDescription ?? "<unknown>"
                os_log("Failed to fetch storefront error: %{public}@", log: OSLog.default, type: .error, errorString)
                completion(nil)
                return
            }
            os_log("Fetched storefront: %{public}@", log: OSLog.default, type: .info, code)
            completion(code)
        }
    }

    /// Creates the URL to access the Apple Music API.
    private func composeAppleMusicAPIURL(_ path: String, parameters: [String: String]?) -> URL? {
        var components = URLComponents(string: AppleMusicAPIController.baseURL)!
        components.path = path
        if let resolvedParameters = parameters, !resolvedParameters.isEmpty {
            components.queryItems = resolvedParameters.map { name, value in URLQueryItem(name: name, value: value) }
        }
        return components.url
    }

    /// Executes a specified fetch.
    private func executeFetch<T: Decodable>(_ type: T.Type, url: URL?, completion: @escaping (T?) -> Void) {
        guard let resolvedURL = url, let resolvedUserToken = userToken else {
            completion(nil)
            return
        }
        var request = URLRequest(url: resolvedURL)
        request.addValue(AppleMusicAPIController.authorizationHeader, forHTTPHeaderField: "Authorization")
        request.addValue(resolvedUserToken, forHTTPHeaderField: "Music-User-Token")

        URLSession.shared.dataTask(with: request) { data, response, error in
            guard let urlResponse = response as? HTTPURLResponse, urlResponse.statusCode == 200, let resolvedData = data else {
                let errorString = error?.localizedDescription ?? "<unknown>"
                os_log("Failed to fetch data error: %{public}@", log: OSLog.default, type: .error, errorString)
                completion(nil)
                return
            }
            let jsonDecoder = JSONDecoder()
            jsonDecoder.dateDecodingStrategy = JSONDecoder.DateDecodingStrategy.custom { dateDecoder -> Date in
                let string = try dateDecoder.singleValueContainer().decode(String.self)
                guard let date = self.dateDecoder.decode(string) else {
                    throw DecodingError.dataCorrupted(
                        DecodingError.Context(codingPath: dateDecoder.codingPath,
                                              debugDescription: "Expected date string to be ISO8601 or yyyy-MM-dd formatted."))
                }

                return date
            }
            let decodedResult = try? jsonDecoder.decode(T.self, from: resolvedData)
            completion(decodedResult)
        }.resume()
    }

    /// Performs a search according to SearchType.
    private func performSearchOfType(_ type: SearchType, term: String, completion: @escaping (SearchResponse?) -> Void) {
        guard let resolvedStorefront = storefront else {
            completion(nil)
            return
        }
        let path = "/v1/catalog/\(resolvedStorefront)/search"
        let url = composeAppleMusicAPIURL(path, parameters: ["types": type.rawValue, "term": term])
        executeFetch(SearchResponse.self, url: url, completion: completion)
    }
    
    /// Resolves whether the SearchResults are from an artist, playlist, or album.
    private func resolveArtistPlaylistOrAlbumFromSearchResults(_ searchResults: SearchResults, completion: @escaping ([Any]?) -> Void) {
        guard let artistPath = searchResults.artists?.data.first?.href else {
            completion(nil)
            return
        }

        let url = self.composeAppleMusicAPIURL(artistPath, parameters: ["include": "albums,playlists"])
        self.executeFetch(ArtistResponse.self, url: url, completion: { artistResponse in
            guard let completeArtistRelationships = artistResponse?.data.first?.relationships else {
                completion(nil)
                return
            }
            completion(completeArtistRelationships.playlists?.data ?? completeArtistRelationships.albums?.data)
        })
    }

}

extension AppleMusicAPIController {
    
    // MARK: Public API Methods
    
    /// Search for music by artist name.
    func searchForArtist(_ artistName: String?, completion: @escaping ([Any]?) -> Void) {
        guard let searchTerm = artistName else {
            completion(nil)
            return
        }
        performSearchOfType(.artist, term: searchTerm) { searchResponse in
            guard let searchResults = searchResponse?.results else {
                completion(nil)
                return
            }
            self.resolveArtistPlaylistOrAlbumFromSearchResults(searchResults, completion: completion)
        }
    }
    
    /// Search for music by song, album, or artist name.
    func searchForSong(_ songName: String?, albumName: String?, artistName: String?, completion: @escaping ([Song]?) -> Void) {
        guard var searchTerm = songName else {
            completion(nil)
            return
        }

        if let resolvedAlbumName = albumName {
            searchTerm += " \(resolvedAlbumName)"
        }

        if let resolvedArtistName = artistName {
            searchTerm += " \(resolvedArtistName)"
        }

        performSearchOfType(.song, term: searchTerm) { searchResponse in
            completion(searchResponse?.results.songs?.data)
        }
    }
    
    /// Search for music by album or artist name.
    func searchForAlbum(_ albumName: String?, artistName: String?, completion: @escaping ([Album]?) -> Void) {
        guard var searchTerm = albumName else {
            completion(nil)
            return
        }

        if let artistName = artistName {
            searchTerm += " \(artistName)"
        }

        performSearchOfType(.album, term: searchTerm) { searchResponse in
            completion(searchResponse?.results.albums?.data)
        }
    }
    
    /// Search for media by name.
    func searchForMedia(_ mediaName: String?, completion: @escaping ([Any]?) -> Void) {
        guard let searchTerm = mediaName else {
            completion(nil)
            return
        }

        performSearchOfType(.media, term: searchTerm) { searchResponse in
            guard let searchResults = searchResponse?.results else {
                completion(nil)
                return
            }

            // In this sample application, with no specified media type, prefer artists, then albums, then songs.
            self.resolveArtistPlaylistOrAlbumFromSearchResults(searchResults, completion: { playlistsOrAlbums in
                completion(playlistsOrAlbums ?? searchResults.albums?.data ?? searchResults.songs?.data)
            })
        }
    }
    
    /// Search for music with a specified search term.
    func searchForTerm(_ term: String?, completion: @escaping ([Song]?) -> Void) {
        guard let searchTerm = term else {
            completion(nil)
            return
        }
        performSearchOfType(.song, term: searchTerm) { (searchResponse) in
            completion(searchResponse?.results.songs?.data)
        }
    }
    
    /// Search the song charts.
    func searchSongCharts(completion: @escaping ([Chart]?) -> Void) {
        fetchCharts(.song) { (response) in
            completion(response?.results.songs)
        }
    }
    
    /// Search for music by an identifier.
    func fetchSongByIdentifier(_ identifier: String?, completion: @escaping (Song?) -> Void) {
        guard let resolvedIdentifier = identifier, let resolvedStorefront = storefront, !resolvedIdentifier.isEmpty else {
            completion(nil)
            return
        }

        let path = "/v1/catalog/\(resolvedStorefront)/songs/\(resolvedIdentifier)"
        let url = composeAppleMusicAPIURL(path, parameters: nil)
        executeFetch(SongResponse.self, url: url) { songResponse in
            completion(songResponse?.data.first)
        }
    }
    
    /// Search for charts by chart type.
    private func fetchCharts(_ type: ChartType, completion: @escaping (ChartsResponse?) -> Void) {
        guard let resolvedStorefront = storefront else {
            completion(nil)
            return
        }
        let path = "/v1/catalog/\(resolvedStorefront)/charts"
        let url = composeAppleMusicAPIURL(path, parameters: ["types": type.rawValue, "limit": "20"])
        executeFetch(ChartsResponse.self, url: url, completion: completion)
    }
    
    /// Fetch an image by media item identifier.
    func fetchUIImageForIdentifier(_ identifier: String?, ofSize: CGSize, completion: @escaping (UIImage?) -> Void) {
        guard let resolvedIdentifier = identifier else {
            completion(nil)
            return
        }
        
        // Fetch the song via its store identifier.
        fetchSongByIdentifier(resolvedIdentifier, completion: { optionalSong in
            guard let song = optionalSong, var artworkURLString = song.attributes?.artwork.url else {
                completion(nil)
                return
            }
            
            // Convert the placeholder value in the URL to the required size.
            artworkURLString = artworkURLString.replacingOccurrences(of: "{w}x{h}", with: String(format: "%.0fx%.0f", ofSize.width, ofSize.height))
            guard let artworkURL = URL(string: artworkURLString) else {
                completion(nil)
                return
            }
            
            // Execute a network fetch for the artwork URL.
            URLSession.shared.dataTask(with: URLRequest(url: artworkURL)) { data, response, error in
                guard let urlResponse = response as? HTTPURLResponse, urlResponse.statusCode == 200, let resolvedData = data else {
                    let errorString = error?.localizedDescription ?? "<unknown>"
                    os_log("Failed to fetch artwork data error: %{public}@", log: OSLog.default, type: .error, errorString)
                    completion(nil)
                    return
                }
                
                // Convert the data to a UIImage and call the completion handler.
                let image = UIImage(data: resolvedData)
                completion(image)
            }.resume()
        })
    }
}
