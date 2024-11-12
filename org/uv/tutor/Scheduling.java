package org.uv.tutor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.uv.tutor.core.collab.GroupMaker;
import org.uv.tutor.core.exceptions.NotFoundException;
import org.uv.tutor.core.utils.AsyncSimpMessageTemplate;
import org.uv.tutor.models.Wrapper;
import org.uv.tutor.payload.response.WebSocketResponse;
import org.uv.tutor.schema.UserInWaitRoom;
import org.uv.tutor.services.WaitroomService;
import org.uv.tutor.services.WrapperService;

import lombok.val;

@Component
@ConditionalOnProperty(value = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class Scheduling {
    @Autowired
    WaitroomService waitroomService;
    @Autowired
    GroupMaker groupMaker;
    @Autowired
    AsyncSimpMessageTemplate messagingTemplate;
    @Autowired
    WrapperService wrapperService;
    @Autowired
    ResourceBundleMessageSource messageSource;
    @Autowired
    RedissonClient redisson;

    private final Logger logger = LoggerFactory.getLogger(Scheduling.class.getName());
    private final int FIXED_DELAY_MS = 200;

    /**
     * This function runs periodically every FIXED_DELAY_MS milliseconds to manage
     * the creation of chat rooms. Depending on the user's request, the
     * behavior varies: if a user requests to join an individual collection,
     * a specific room is created for them, and the room identifier (roomId)
     * is sent via WebSocket. In the case of requests for group collections, the
     * user remains in a waiting state until a sufficient number of people gather.
     * Once this number is reached, a group room is created, and the roomId is sent
     * to all members via WebSocket. Since the sending of roomId via WebSocket can
     * occasionally fail, an alternative endpoint 'getRoomId' is provided in the
     * 'ChatController'.
     * 
     * The process is carried out as follows: the function retrieves the waiting
     * users for each room, groups them according to the grouping strategy defined
     * by the collection (individual, pairs, etc.). Then, for each user who has been
     * assigned to a room, the corresponding roomId is sent via WebSocket.
     * 
     * @throws ExecutionException
     * @throws InterruptedException
     */

    @Scheduled(fixedDelay = FIXED_DELAY_MS, timeUnit = TimeUnit.MILLISECONDS)
    public void doCheckWaitRooms() throws InterruptedException, ExecutionException {
        List<Future<?>> messagingTasks = new ArrayList<>();
        Set<Long> waitroomsIds = waitroomService.getWaitRoomKeysByWrapperIdUnique();
        if (!waitroomsIds.isEmpty())
            logger.info("Found {} users waiting to join a collection", waitroomsIds.size());
        for (Long waitroomId : waitroomsIds) {
            try {
                List<UserInWaitRoom> usersInWaitRoom = waitroomService.getUsersInWaitRoom(waitroomId);
                logger.info("Found {} users waiting in room {}", usersInWaitRoom.size(), waitroomId);
                Wrapper wrapper = wrapperService.get(waitroomId);
                logger.info("A group is being made with a {} strategy", wrapper.getSettings().getGroupStrategy());
                List<UserInWaitRoom> groupedUsers = groupMaker.withStrategy(wrapper.getSettings().getGroupStrategy())
                        .tryAndMakeGroups(usersInWaitRoom, wrapper.getName());

                AtomicInteger indexHolder = new AtomicInteger();
                logger.info("Grouped {} users in room", groupedUsers.size());
                for (UserInWaitRoom userInWaitRoom : groupedUsers) {
                    logger.info("Notifying user {}", userInWaitRoom.getUser().getUsername());
                    WebSocketResponse response = new WebSocketResponse(userInWaitRoom.getRoomName(),
                            indexHolder.getAndIncrement()); // TODO: is this indexHolder used by the client? it should
                                                            // be removed (maybe) @Pablo
                    messagingTasks.add(messagingTemplate.asyncConvertAndSend(
                            "/topic/client-" + userInWaitRoom.getUser().getUsername(),
                            response));

                    logger.info("USER={}, ROOM={}, WRAPPER={}", userInWaitRoom.getUser().getUsername(),
                            userInWaitRoom.getRoomName(),
                            wrapper.getName());
                }
                waitroomService.removeUsersFromWaitroom(waitroomId, groupedUsers);

                logger.info("Removing Users that have been waiting too long");
            } catch (NotFoundException e) {
                logger.error("Wrapper with ID {} not found", waitroomId);
            }
        }

        for (val task : messagingTasks) {
            task.get();
        }
    }

}
