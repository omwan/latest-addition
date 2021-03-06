package com.omwan.latestadditions.service;

import com.omwan.latestadditions.component.SpotifyApiComponent;
import com.omwan.latestadditions.component.UserPlaylistComponent;
import com.omwan.latestadditions.dto.BuildPlaylistRequest;
import com.omwan.latestadditions.dto.LatestPlaylistResponse;
import com.omwan.latestadditions.dto.PlaylistIdWrapper;
import com.wrapper.spotify.SpotifyApi;
import com.wrapper.spotify.model_objects.specification.Paging;
import com.wrapper.spotify.model_objects.specification.Playlist;
import com.wrapper.spotify.model_objects.specification.PlaylistSimplified;
import com.wrapper.spotify.model_objects.specification.PlaylistTrack;
import com.wrapper.spotify.requests.data.AbstractDataRequest;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of services pertaining to spotify playlists.
 */
@Service
public class SpotifyPlaylistServiceImpl implements SpotifyPlaylistService {

    @Autowired
    private SpotifyApiComponent spotifyApiComponent;

    @Autowired
    private UserPlaylistComponent userPlaylistComponent;

    /**
     * Get playlists for the current user.
     *
     * @param limit  max number of playlists to retrieve
     * @param offset offset for playlist pagination
     * @return paging object containing given set of user's playlists.
     */
    @Override
    public Paging<PlaylistSimplified> getUserPlaylists(int limit, int offset) {
        if (!spotifyApiComponent.tokensExist()) {
            return null;
        }

        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();

        AbstractDataRequest usersPlaylistsRequest = spotifyApi
                .getListOfCurrentUsersPlaylists()
                .limit(limit)
                .offset(offset)
                .build();
        return spotifyApiComponent.executeRequest(usersPlaylistsRequest,
                "Unable to retrieve user playlists");
    }

    /**
     * Get details for a given playlist.
     *
     * @param playlistId string containing user and playlist IDs
     * @return playlist details object for given user and playlist ID
     */
    @Override
    public Playlist getPlaylistDetails(String playlistId) {
        String fields = String.join(",", Arrays.asList("description",
                "external_urls", "href", "images", "name", "owner",
                "tracks.total", "uri", "isCollaborative", "isPublicAccess"));

        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();
        String userId = spotifyApiComponent.getCurrentUserId();

        AbstractDataRequest playlistDetailsRequest = spotifyApi
                .getPlaylist(userId, playlistId)
                .fields(fields)
                .build();
        String errorMessage = "Unable to retrieve playlist details for playlist " + playlistId;
        return spotifyApiComponent.executeRequest(playlistDetailsRequest, errorMessage);
    }

    /**
     * Create or update latest additions playlist with the specified requirements.
     *
     * @param request playlist specifications
     * @return track preview for created/updated playlist
     */
    @Override
    public LatestPlaylistResponse buildLatestAdditionsPlaylist(BuildPlaylistRequest request) {
        String userId = spotifyApiComponent.getCurrentUserId();
        List<PlaylistIdWrapper> playlists = request.getPlaylistIds().keySet().stream()
                .map((String playlistId) -> new PlaylistIdWrapper(playlistId, userId))
                .collect(Collectors.toList());

        Map<PlaylistIdWrapper, LinkedList<PlaylistTrack>> playlistTracks =
                getPlaylistTracks(request, playlists);

        List<PlaylistTrack> latestAdditionsTracks = getLatestAdditions(request, playlistTracks);
        String[] trackUris = latestAdditionsTracks.stream()
                .map(playlistTrack -> playlistTrack.getTrack().getUri())
                .collect(Collectors.toList())
                .toArray(new String[latestAdditionsTracks.size()]);

        PlaylistIdWrapper playlistWrapper;
        if (request.isOverwriteExisting()) {
            playlistWrapper = overwriteExistingLatestAdditions(request.getPlaylistToOverwrite(), trackUris, userId);
        } else {
            playlistWrapper = createNewLatestAdditions(trackUris, userId, request);
        }
        return buildTrackPreviewResponse(playlistWrapper, latestAdditionsTracks);
    }

    /**
     * Build a response object containing a link to the newly generated or updated playlist,
     * as well as a preview the tracks in the playlist.
     *
     * @param playlistWrapper playlist info
     * @param tracks          list of tracks in playlist
     * @return response object
     */
    private LatestPlaylistResponse buildTrackPreviewResponse(PlaylistIdWrapper playlistWrapper,
                                                             List<PlaylistTrack> tracks) {
        LatestPlaylistResponse response = new LatestPlaylistResponse();
        response.setPlaylistUrl(String.format("https://open.spotify.com/user/%s/playlist/%s",
                playlistWrapper.getUserId(), playlistWrapper.getPlaylistId()));
        response.setTracklistPreview(tracks.subList(0, Math.min(tracks.size(), 10)));
        return response;
    }

    /**
     * Build a mapping of playlists to a LinkedList of their most recent tracks.
     *
     * @param request   playlist specifications
     * @param playlists playlists to retrieve tracks for
     * @return mapping of playlists to their tracks
     */
    private Map<PlaylistIdWrapper, LinkedList<PlaylistTrack>> getPlaylistTracks(BuildPlaylistRequest request,
                                                                                List<PlaylistIdWrapper> playlists) {
        Map<PlaylistIdWrapper, LinkedList<PlaylistTrack>> playlistTracks = new HashMap<>();

        for (PlaylistIdWrapper playlist : playlists) {
            int limit = request.getNumTracks();
            int playlistSize = request.getPlaylistIds().get(playlist.getPlaylistId());
            int offset = playlistSize - request.getNumTracks();
            if (offset < 0) {
                offset = 0;
            }
            playlist.setOffset(offset);
            PlaylistTrack[] tracks = getTracksForPlaylist(playlist, limit, offset);
            playlistTracks.put(playlist, new LinkedList<>(Arrays.asList(tracks)));
        }

        return playlistTracks;
    }

    /**
     * Generate a list of "latest additions" tracks from the given set of playlists.
     *
     * @param request        playlist specifications
     * @param playlistTracks mapping of playlist IDs to their tracks
     * @return list of tracks for latest additions playlist
     */
    private List<PlaylistTrack> getLatestAdditions(BuildPlaylistRequest request,
                                                   Map<PlaylistIdWrapper, LinkedList<PlaylistTrack>> playlistTracks) {
        List<PlaylistTrack> latestAdditionsTracks = new ArrayList<>();

        PlaylistTrack lastAdded = null;
        PlaylistIdWrapper lastAddedPlaylist = null;

        Map<PlaylistIdWrapper, PlaylistTrack> lastAddedTracks = new HashMap<>();
        for (PlaylistIdWrapper playlist : playlistTracks.keySet()) {
            playlist.setSkipCount(0);
            lastAddedTracks.put(playlist, getNextTrack(playlist, playlistTracks,
                    lastAddedTracks.values()));
        }

        int count = 0;
        while (count < request.getNumTracks()) {
            for (PlaylistIdWrapper playlist : lastAddedTracks.keySet()) {
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
            count++;

            Collection<PlaylistTrack> comparedTracks = new ArrayList<>();
            comparedTracks.addAll(latestAdditionsTracks);
            comparedTracks.addAll(lastAddedTracks.values());

            PlaylistTrack nextTrack = getNextTrack(lastAddedPlaylist, playlistTracks, comparedTracks);
            lastAddedTracks.put(lastAddedPlaylist, nextTrack);
            lastAdded = null;
        }

        return latestAdditionsTracks;
    }

    /**
     * Retrieve the last added track for a given playlist, skipping local files
     * and duplicate tracks.
     *
     * @param playlist         playlist to retrieve track for
     * @param playlistTrackMap mapping of playlists to tracks
     * @param comparedTracks   tracks to compare for duplicates
     * @return next track for the given playlist
     */
    private PlaylistTrack getNextTrack(PlaylistIdWrapper playlist,
                                       Map<PlaylistIdWrapper, LinkedList<PlaylistTrack>> playlistTrackMap,
                                       Collection<PlaylistTrack> comparedTracks) {
        LinkedList<PlaylistTrack> playlistTracks = playlistTrackMap.get(playlist);
        if (!(playlistTracks.isEmpty())) {
            PlaylistTrack nextTrack = playlistTracks.removeLast();
            if (nextTrack.getIsLocal() || duplicateTrack(comparedTracks, nextTrack)) {
                playlist.setSkipCount(playlist.getSkipCount() + 1);
                return getNextTrack(playlist, playlistTrackMap, comparedTracks);
            } else {
                return nextTrack;
            }
        } else {
            int skips = playlist.getSkipCount();
            if (skips != 0) {
                if (skips <= 10) {
                    skips = 10;
                }
                int offset = Math.max(playlist.getOffset() - skips, 0);
                PlaylistTrack[] moreTracks = getTracksForPlaylist(playlist, skips,
                        offset);
                if (moreTracks.length == 0) {
                    return null;
                }
                playlistTracks.addAll(Arrays.asList(moreTracks));
                playlistTrackMap.put(playlist, playlistTracks);
                playlist.setOffset(offset);
                playlist.setSkipCount(0);
                return getNextTrack(playlist, playlistTrackMap, comparedTracks);
            }
            return null;
        }
    }

    /**
     * Check if a track is in the given list of tracks.
     *
     * @param tracks        track to compare
     * @param playlistTrack list of tracks to compare
     * @return whether or not the track is present in the list
     */
    private boolean duplicateTrack(Collection<PlaylistTrack> tracks, PlaylistTrack playlistTrack) {
        for (PlaylistTrack track : tracks) {
            if (track.getTrack().getUri().equals(playlistTrack.getTrack().getUri())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Overwrite an existing playlist with the "latest additions" tracks.
     *
     * @param playlistToOverwrite playlist to overwrite
     * @param trackUris           list of track URIs for playlist
     * @param userId              user ID of current user
     */
    private PlaylistIdWrapper overwriteExistingLatestAdditions(String playlistToOverwrite,
                                                               String[] trackUris,
                                                               String userId) {
        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();
        PlaylistIdWrapper playlistIdWrapper = new PlaylistIdWrapper(playlistToOverwrite, userId);
        AbstractDataRequest replaceTracksRequest = spotifyApi
                .replacePlaylistsTracks(userId, playlistIdWrapper.getPlaylistId(), trackUris)
                .build();

        String errorMessage = "Unable to replace tracks for playlist %s" + playlistIdWrapper;
        spotifyApiComponent.executeRequest(replaceTracksRequest, errorMessage);

        return playlistIdWrapper;
    }

    /**
     * Create a new playlist containing the "latest additions" tracks.
     *
     * @param trackUris list of track URIs for playlist
     * @param userId    user ID of current user
     * @param request   playlist specifications
     */
    private PlaylistIdWrapper createNewLatestAdditions(String[] trackUris,
                                                       String userId,
                                                       BuildPlaylistRequest request) {
        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();
        AbstractDataRequest createPlaylistRequest = spotifyApi
                .createPlaylist(userId, request.getPlaylistName())
                .description(request.getDescription())
                .collaborative(request.isCollaborative())
                .public_(request.isPublic())
                .build();

        String createPlaylistErrorMessage = "Unable to create playlist with given parameters " +
                ToStringBuilder.reflectionToString(request);
        Playlist latestAdditions = spotifyApiComponent.executeRequest(createPlaylistRequest,
                createPlaylistErrorMessage);

        PlaylistIdWrapper playlistIdWrapper = new PlaylistIdWrapper(latestAdditions.getId(), userId);
        AbstractDataRequest addTracksRequest = spotifyApi
                .addTracksToPlaylist(userId, playlistIdWrapper.getPlaylistId(), trackUris)
                .build();

        String addTracksErrorMessage = "Unable to add tracks to playlist " + playlistIdWrapper.getPlaylistId();
        spotifyApiComponent.executeRequest(addTracksRequest, addTracksErrorMessage);
        userPlaylistComponent.saveUserPlaylist(userId, playlistIdWrapper.getPlaylistId());

        return playlistIdWrapper;
    }

    /**
     * Retrieve the tracks for an individual playlist.
     *
     * @param playlistWrapper wrapper object of playlist to retrieve tracks for
     * @param limit           maximum number of tracks to retrieve
     * @param offset          offset to start retrieving tracks from
     * @return array of playlist tracks
     */
    private PlaylistTrack[] getTracksForPlaylist(PlaylistIdWrapper playlistWrapper, int limit, int offset) {
        SpotifyApi spotifyApi = spotifyApiComponent.getApiWithTokens();

        AbstractDataRequest trackRequest = spotifyApi
                .getPlaylistsTracks(playlistWrapper.getUserId(), playlistWrapper.getPlaylistId())
                .limit(limit)
                .offset(offset)
                .build();

        String errorMessage = "Unable to retrieve tracks for playlist " + playlistWrapper.toString();
        Paging<PlaylistTrack> tracks = spotifyApiComponent.executeRequest(trackRequest, errorMessage);
        return tracks.getItems();
    }


}
