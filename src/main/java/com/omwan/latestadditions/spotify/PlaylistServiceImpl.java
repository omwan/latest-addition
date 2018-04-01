package com.omwan.latestadditions.spotify;

import com.omwan.latestadditions.component.SpotifyApiComponent;
import com.omwan.latestadditions.component.UriComponent;
import com.omwan.latestadditions.dto.BuildPlaylistRequest;
import com.omwan.latestadditions.dto.PlaylistUri;
import com.wrapper.spotify.SpotifyApi;
import com.wrapper.spotify.model_objects.specification.Paging;
import com.wrapper.spotify.model_objects.specification.Playlist;
import com.wrapper.spotify.model_objects.specification.PlaylistSimplified;
import com.wrapper.spotify.model_objects.specification.PlaylistTrack;
import com.wrapper.spotify.model_objects.specification.User;
import com.wrapper.spotify.requests.data.AbstractDataRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Services pertaining to spotify API.
 */
@Service
public class PlaylistServiceImpl implements PlaylistService {

    @Autowired
    private SpotifyApiComponent spotifyApiComponent;

    @Autowired
    private UriComponent uriComponent;

    @Autowired
    @SuppressWarnings("SpringJavaAutowiringInspection")
    private HttpSession session;

    /**
     * Get playlists for the current user.
     *
     * @param limit  max number of playlists to retrieve
     * @param offset offset for playlist pagination
     * @return paging object containing given set of user's playlists.
     */
    @Override
    public Paging<PlaylistSimplified> getUserPlaylists(int limit, int offset) {
        if (session.getAttribute("ACCESS_TOKEN") == null) {
            return null;
        }

        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();

        AbstractDataRequest usersPlaylistsRequest = spotifyApi
                .getListOfCurrentUsersPlaylists()
                .limit(limit)
                .offset(offset)
                .build();
        return spotifyApiComponent.executeRequest(usersPlaylistsRequest, "Unable to retrieve user playlists");
    }

    /**
     * Get details for a given playlist.
     *
     * @param playlistURI string containing user and playlist IDs
     * @return playlist details object for given user and playlist ID
     */
    @Override
    public Playlist getPlaylistDetails(String playlistURI) {
        String fields = String.join(",", Arrays.asList("description",
                "external_urls", "href", "images", "name", "owner",
                "tracks.total", "uri", "isCollaborative", "isPublicAccess"));
        PlaylistUri playlistURIWrapper = uriComponent.buildPlaylistURI(playlistURI);

        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();

        AbstractDataRequest playlistDetailsRequest = spotifyApi
                .getPlaylist(playlistURIWrapper.getUserId(), playlistURIWrapper.getPlaylistId())
                .fields(fields)
                .build();
        return spotifyApiComponent.executeRequest(playlistDetailsRequest, "Unable to retrieve playlist details");
    }

    @Override
    public PlaylistUri buildLatestAdditionsPlaylist(BuildPlaylistRequest request) {
        List<PlaylistUri> playlists = request.getPlaylistUris().keySet().stream()
                .map(uriComponent::buildPlaylistURI)
                .collect(Collectors.toList());

        Map<PlaylistUri, LinkedList<PlaylistTrack>> playlistTracks = getPlaylistTracks(request, playlists);

        List<PlaylistTrack> latestAdditionsTracks = getLatestAdditions(request, playlistTracks);

        String userId = getUserId();
        String[] trackUris = latestAdditionsTracks.stream()
                .map(playlistTrack -> playlistTrack.getTrack().getUri())
                .collect(Collectors.toList())
                .toArray(new String[latestAdditionsTracks.size()]);

        if (request.isOverwriteExisting()) {
            return overwriteExistingLatestAdditions(trackUris, userId);
        } else {
            return createNewLatestAdditions(trackUris, userId, request);
        }
    }

    private Map<PlaylistUri, LinkedList<PlaylistTrack>> getPlaylistTracks(BuildPlaylistRequest request,
                                                                          List<PlaylistUri> playlists) {
        Map<PlaylistUri, LinkedList<PlaylistTrack>> playlistTracks = new HashMap<>();

        for (PlaylistUri playlist : playlists) {
            int limit = request.getNumTracks();
            int playlistSize = request.getPlaylistUris().get(playlist.toString());
            int offset = playlistSize - request.getNumTracks();
            PlaylistTrack[] tracks = getTracksForPlaylist(playlist, limit, offset);
            playlistTracks.put(playlist, new LinkedList<>(Arrays.asList(tracks)));
        }

        return playlistTracks;
    }

    private List<PlaylistTrack> getLatestAdditions(BuildPlaylistRequest request,
                                                   Map<PlaylistUri, LinkedList<PlaylistTrack>> playlistTracks) {
        List<PlaylistTrack> latestAdditionsTracks = new ArrayList<>();

        PlaylistTrack lastAdded = null;
        PlaylistUri lastAddedPlaylist = null;

        Map<PlaylistUri, PlaylistTrack> lastAddedTracks = new HashMap<>();
        for (PlaylistUri playlist : playlistTracks.keySet()) {
            lastAddedTracks.put(playlist, playlistTracks.get(playlist).removeLast());
        }

        int count = 0;
        while (count < request.getNumTracks()) {
            for (PlaylistUri playlist : lastAddedTracks.keySet()) {
                if (lastAddedTracks.get(playlist) != null) {
                    if (lastAdded == null) {
                        lastAdded = lastAddedTracks.get(playlist);
                        lastAddedPlaylist = playlist;
                    } else {
                        PlaylistTrack currentTrack = lastAddedTracks.get(playlist);
                        if (currentTrack.getAddedAt().after(lastAdded.getAddedAt())) {
                            lastAdded = currentTrack;
                            lastAddedPlaylist = playlist;
                        }
                    }
                }
            }

            latestAdditionsTracks.add(lastAdded);
            if (!playlistTracks.get(lastAddedPlaylist).isEmpty()) {
                lastAddedTracks.put(lastAddedPlaylist, playlistTracks.get(lastAddedPlaylist).removeLast());
            } else {
                lastAddedTracks.put(lastAddedPlaylist, null);
            }
            lastAdded = null;
            count++;
        }

        return latestAdditionsTracks;
    }

    private PlaylistUri overwriteExistingLatestAdditions(String[] trackUris,
                                                         String userId) {
        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();
        String uri = userId.concat(userId);
        PlaylistUri playlistUri = uriComponent.buildPlaylistURI(uri);
        AbstractDataRequest replaceTracksRequest = spotifyApi
                .replacePlaylistsTracks(userId, playlistUri.getPlaylistId(), trackUris)
                .build();
        spotifyApiComponent.executeRequest(replaceTracksRequest, "ugh");
        return playlistUri;
    }

    private PlaylistUri createNewLatestAdditions(String[] trackUris,
                                                 String userId,
                                                 BuildPlaylistRequest request) {
        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();
        AbstractDataRequest createPlaylistRequest = spotifyApi
                .createPlaylist(userId, request.getPlaylistName())
                .description("Autogenerated playlist")
                .collaborative(request.isCollaborative())
                .public_(request.isPublic())
                .build();
        Playlist latestAdditions = spotifyApiComponent.executeRequest(createPlaylistRequest, "ugh");
        PlaylistUri playlistUri = uriComponent.buildPlaylistURI(latestAdditions.getUri());
        AbstractDataRequest addTracksRequest = spotifyApi
                .addTracksToPlaylist(userId, playlistUri.getPlaylistId(), trackUris)
                .build();
        spotifyApiComponent.executeRequest(addTracksRequest, "ugh");
        return playlistUri;
    }

    private String getUserId() {
        if (session.getAttribute("USER_ID") == null) {
            SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();
            AbstractDataRequest userRequest = spotifyApi.getCurrentUsersProfile()
                    .build();
            User user = spotifyApiComponent.executeRequest(userRequest, "ugh");
            session.setAttribute("USER_ID", user.getId());
            return user.getId();
        } else {
            return session.getAttribute("USER_ID").toString();
        }
    }

    private PlaylistTrack[] getTracksForPlaylist(PlaylistUri uriWrapper, int limit, int offset) {
        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();

        if (spotifyApi == null) {
            throw new RuntimeException("ugh");
        }
        AbstractDataRequest trackRequest = spotifyApi
                .getPlaylistsTracks(uriWrapper.getUserId(), uriWrapper.getPlaylistId())
                .limit(limit)
                .offset(offset)
                .build();
        Paging<PlaylistTrack> tracks = spotifyApiComponent.executeRequest(trackRequest, "ugh");
        return tracks.getItems();
    }


}