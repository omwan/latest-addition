package com.omwan.latestadditions.spotify;

import com.omwan.latestadditions.dto.PlaylistURIWrapper;
import com.omwan.latestadditions.utils.URIUtils;
import com.wrapper.spotify.SpotifyApi;
import com.wrapper.spotify.exceptions.SpotifyWebApiException;
import com.wrapper.spotify.exceptions.detailed.UnauthorizedException;
import com.wrapper.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import com.wrapper.spotify.model_objects.specification.Paging;
import com.wrapper.spotify.model_objects.specification.Playlist;
import com.wrapper.spotify.model_objects.specification.PlaylistSimplified;
import com.wrapper.spotify.requests.authorization.authorization_code.AuthorizationCodeRequest;
import com.wrapper.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;
import com.wrapper.spotify.requests.data.AbstractDataRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Services pertaining to spotify API.
 */
@Service
public class SpotifyServiceImpl implements SpotifyService {

    @Autowired
    @Qualifier("spotify")
    private SpotifyApi spotifyApi;

    @Autowired
    private URIUtils uriUtils;

    /**
     * Make authorization request for API usage.
     *
     * @param response to redirect to auth request page.
     */
    @Override
    public void authorize(HttpServletResponse response) {
        List<String> scopes = Arrays.asList(
                "playlist-read-private",
                "playlist-modify-private",
                "playlist-modify-public",
                "playlist-read-collaborative");

        AuthorizationCodeUriRequest authorizationCodeUriRequest = spotifyApi.authorizationCodeUri()
                .scope(String.join(", ", scopes))
                .show_dialog(true)
                .build();
        URI redirectURI = authorizationCodeUriRequest.execute();

        try {
            response.sendRedirect(redirectURI.toString());
        } catch (IOException e) {
            throw new RuntimeException("Could not redirect to permissions page", e);
        }
    }

    /**
     * Set the access token for the spotifyApi instance as given by the redirect.
     *
     * @param token    access token
     * @param response to redirect to application homepage after receiving auth token.
     */
    @Override
    public void setToken(String token, HttpServletResponse response) {
        AuthorizationCodeRequest authorizationCodeRequest = spotifyApi.authorizationCode(token)
                .build();
        try {
            AuthorizationCodeCredentials authorizationCodeCredentials = authorizationCodeRequest.execute();
            spotifyApi.setAccessToken(authorizationCodeCredentials.getAccessToken());
            spotifyApi.setRefreshToken(authorizationCodeCredentials.getRefreshToken());
            response.sendRedirect("/");
        } catch (IOException | SpotifyWebApiException e) {
            throw new RuntimeException("Could not retrieve auth code credentials", e);
        }
    }

    /**
     * Get playlists for the current user.
     *
     * @param limit  max number of playlists to retrieve
     * @param offset offset for playlist pagination
     * @return paging object containing given set of user's playlists.
     */
    @Override
    public Paging<PlaylistSimplified> getUserPlaylists(int limit, int offset) {
        AbstractDataRequest usersPlaylistsRequest = spotifyApi.getListOfCurrentUsersPlaylists()
                .limit(limit)
                .offset(offset)
                .build();
        return executeRequest(usersPlaylistsRequest, "Unable to retrieve user playlists");
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
                "externalUrls", "href", "images", "name", "owner", ",tracks.total,",
                "uri", "isCollaborative", "isPublicAccess"));

        PlaylistURIWrapper playlistURIWrapper = uriUtils.buildPlaylistURI(playlistURI);
        String userId = playlistURIWrapper.getUserId();
        String playlistId = playlistURIWrapper.getPlaylistId();

        AbstractDataRequest playlistDetailsRequest = spotifyApi.getPlaylist(userId, playlistId)
                .fields(fields)
                .build();
        return executeRequest(playlistDetailsRequest, "Unable to retrieve playlist");
    }

    /**
     * Helper to execute API requests.
     *
     * @param requestBuilder request to execute
     * @param errorMessage   error message to log in event of failure
     * @param <T>            return type of request
     * @return value of executed request
     */
    private <T> T executeRequest(AbstractDataRequest requestBuilder,
                                 String errorMessage) {
        if (spotifyApi.getAccessToken() == null) {
            return null;
        }

        try {
            return requestBuilder.execute();
        } catch (UnauthorizedException e) {
            refreshToken();
            return null;
        } catch (IOException | SpotifyWebApiException e) {
            throw new RuntimeException(errorMessage, e);
        }
    }

    /**
     * Refresh access token for spotify api.
     */
    private void refreshToken() {
        try {
            AuthorizationCodeCredentials authorizationCodeCredentials = spotifyApi.authorizationCodeRefresh()
                    .build()
                    .execute();
            spotifyApi.setAccessToken(authorizationCodeCredentials.getAccessToken());
            spotifyApi.setRefreshToken(authorizationCodeCredentials.getRefreshToken());

            System.out.println(String.format("Token successfully refreshed, expires in %s",
                    authorizationCodeCredentials.getExpiresIn()));
        } catch (IOException | SpotifyWebApiException e) {
            throw new RuntimeException("Unable to refresh access token", e);
        }
    }

}