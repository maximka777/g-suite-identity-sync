package eu.hlavki.identity.services.google.impl;

import com.google.common.base.Supplier;
import static com.google.common.base.Suppliers.memoizeWithExpiration;
import eu.hlavki.identity.services.google.GSuiteDirectoryService;
import eu.hlavki.identity.services.google.InvalidPasswordException;
import eu.hlavki.identity.services.google.ResourceNotFoundException;
import eu.hlavki.identity.services.google.config.Configuration;
import eu.hlavki.identity.services.google.model.*;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import static java.util.concurrent.TimeUnit.*;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.rs.security.oauth2.common.ClientAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static eu.hlavki.identity.services.google.impl.NotificationType.USERS;
import java.util.Optional;
import eu.hlavki.identity.services.google.WatchingService;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;
import static javax.ws.rs.core.Response.Status.OK;

public class GSuiteDirectoryServiceImpl implements GSuiteDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(GSuiteDirectoryServiceImpl.class);

    private final TokenCache tokenCache;
    private final WebClient directoryApiClient;
    private final Configuration config;
    private final Supplier<Map<GSuiteGroup, GroupMembership>> membershipCache;
    private final WatchingService watchingService;


    public GSuiteDirectoryServiceImpl(Configuration config, WebClient directoryApiClient,
            TokenCache tokenCache, WatchingService watchingService) {
        this.config = config;
        this.directoryApiClient = directoryApiClient;
        this.tokenCache = tokenCache;
        this.watchingService = watchingService;
        this.membershipCache = memoizeWithExpiration(() -> getAllGroupMembershipInternal(), 3, MINUTES);
    }


    @Override
    public GroupMembership getGroupMembers(String groupKey) throws ResourceNotFoundException {
        return readGroupMembers(groupKey, null);
    }


    private GroupMembership readGroupMembers(String groupKey, GroupMembership parent) throws ResourceNotFoundException {
        String path = MessageFormat.format("/admin/directory/v1/groups/{0}/members", new Object[]{groupKey});

        WebClient webClient = WebClient.fromClient(directoryApiClient, true).path(path);
        ClientAccessToken accessToken = tokenCache.getToken();
        webClient.authorization(accessToken);
        GroupMembership result;
        try {
            if (parent != null && parent.getNextPageToken() != null) {
                result = webClient.query("pageToken", parent.getNextPageToken()).get(GroupMembership.class);
                result.getMembers().addAll(parent.getMembers());
            } else {
                result = webClient.get(GroupMembership.class);
            }
            return result.getNextPageToken() != null ? readGroupMembers(groupKey, result) : result;
        } catch (NotFoundException e) {
            throw new ResourceNotFoundException("Group " + groupKey + " not found.", e);
        }
    }


    @Override
    public GSuiteGroup getGroup(String groupKey) {
        String path = MessageFormat.format("/admin/directory/v1/groups/{0}", new Object[]{groupKey});

        WebClient webClient = WebClient.fromClient(directoryApiClient, true);
        webClient.authorization(tokenCache.getToken());
        GSuiteGroup group = webClient.path(path).get(GSuiteGroup.class);
        return group;
    }


    @Override
    public GroupList getUserGroups(String userKey) {
        WebClient webClient = WebClient.fromClient(directoryApiClient, true).path("/admin/directory/v1/groups");
        webClient.authorization(tokenCache.getToken());
        if (userKey != null) {
            webClient.query("userKey", userKey);
        }
        GroupList groupList = webClient.query("domain", config.getGSuiteDomain()).get(GroupList.class);
        return groupList;
    }


    @Override
    public GroupList getAllGroups() {
        return getUserGroups(null);
    }


    @Override
    public Map<GSuiteGroup, GroupMembership> getAllGroupMembership(boolean useCache) {
        return useCache ? membershipCache.get() : getAllGroupMembershipInternal();
    }


    @Override
    public GSuiteUsers getAllUsers() {
        return readAllUsers();
    }


    @Override
    public GSuiteUser getUser(String userKey) {
        String path = MessageFormat.format("/admin/directory/v1/users/{0}", new Object[]{userKey});
        WebClient webClient = WebClient.fromClient(directoryApiClient, true);

        ClientAccessToken accessToken = tokenCache.getToken();
        webClient.authorization(accessToken);
        GSuiteUser user = webClient.path(path).get(GSuiteUser.class);
        return user;
    }


    @Override
    public void updateUserPassword(String userKey, String password) throws InvalidPasswordException {
        String path = MessageFormat.format("/admin/directory/v1/users/{0}", new Object[]{userKey});
        WebClient webClient = WebClient.fromClient(directoryApiClient, true);

        ClientAccessToken accessToken = tokenCache.getToken();
        webClient.authorization(accessToken);
        GSuiteUser user = new GSuiteUser();
        user.setPassword(password);
        Response response = webClient.path(path).put(user);
        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            throw new InvalidPasswordException("Can't change password. Response: " + response.readEntity(String.class));
        }
    }


    @Override
    public String getDomainName() {
        return config.getGSuiteDomain();
    }


    @Override
    public String getImplicitGroup() {
        return config.getGSuiteImplicitGroup();
    }


    @Override
    public void enablePushNotifications() {
        String domain = config.getGSuiteDomain();
        WebClient webClient = WebClient.fromClient(directoryApiClient, true).path("/admin/directory/v1/users/watch")
                .query("domain", domain).query("event", "update");
        ClientAccessToken accessToken = tokenCache.getToken();
        webClient.authorization(accessToken);
        StartWatching watchRequest = new StartWatching("https://accounts.hlavki.eu/cxf/push/notify");
        try {
            Watching notification = webClient.post(watchRequest, Watching.class);
            notification.setType(USERS);
            watchingService.addNotification(notification);
            log.info("Push notifications for domain {} successfully enabled", domain);
        } catch (ClientErrorException e) {
            String body = e.getResponse().readEntity(String.class);
            log.error("Cannot register domain wathing for {}.\nResponse: {}", config.getGSuiteDomain(), body);
        }
    }


    @Override
    public void disablePushNotifications() {
        Optional<Watching> watching = watchingService.getNotificationByType(USERS);
        watching.ifPresent(w -> {
            WebClient webClient = WebClient.fromClient(directoryApiClient, true).path("/admin/directory_v1/channels/stop");
            ClientAccessToken accessToken = tokenCache.getToken();
            webClient.authorization(accessToken);
            Response resp = webClient.post(new StopWatching(w));
            Response.StatusType status = resp.getStatusInfo();
            if (status.toEnum() == OK || status.toEnum() == NO_CONTENT || status.toEnum() == NOT_FOUND) {
                log.info("Push notifications successfully stopeed");
                watchingService.removeNotification(w);
            } else {
                log.error("Cannot stop watching domain! Status: {}, Reason: {}", status.getStatusCode(), status.getReasonPhrase());
            }
        });
    }


    private GSuiteUsers readAllUsers() {
        return readAllUsers(null);
    }


    private GSuiteUsers readAllUsers(GSuiteUsers parent) {
        WebClient webClient = WebClient.fromClient(directoryApiClient, true).path("/admin/directory/v1/users");
        ClientAccessToken accessToken = tokenCache.getToken();
        webClient.authorization(accessToken);
        GSuiteUsers result;
        webClient.query("domain", config.getGSuiteDomain());
        if (parent != null && parent.getNextPageToken() != null) {
            result = webClient.query("pageToken", parent.getNextPageToken()).get(GSuiteUsers.class);
            result.getUsers().addAll(parent.getUsers());
        } else {
            result = webClient.get(GSuiteUsers.class);
        }
        return result.getNextPageToken() != null ? readAllUsers(result) : result;
    }


    private Map<GSuiteGroup, GroupMembership> getAllGroupMembershipInternal() {
        GroupList groups = getAllGroups();
        Map<GSuiteGroup, GroupMembership> result = new HashMap<>();
        for (GSuiteGroup group : groups.getGroups()) {
            try {
                result.put(group, getGroupMembers(group.getId()));
            } catch (ResourceNotFoundException e) {
                log.warn("Can't get group members for " + group.getEmail(), e);
            }
        }
        return result;
    }
}
