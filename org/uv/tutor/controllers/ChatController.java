package org.uv.tutor.controllers;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.uv.legacy.tutor.controllers.TutoringPanelController;
import org.uv.legacy.tutor.model.Language;
import org.uv.legacy.tutor.model.SolutionProgressData;
import org.uv.tutor.core.chat.rasa.RasaChatBotProxy;
import org.uv.tutor.core.enums.LogType;
import org.uv.tutor.core.exceptions.NotFoundException;
import org.uv.tutor.core.logging.LogMapUtils;
import org.uv.tutor.core.modelling.UserModel;
import org.uv.tutor.core.modelling.UserModelProxy;
import org.uv.tutor.core.structure.adapters.ProblemAdapter;
import org.uv.tutor.models.EGroupStrategy;
import org.uv.tutor.models.ProblemsCollections;
import org.uv.tutor.models.RealizedProblem;
import org.uv.tutor.models.Report;
import org.uv.tutor.models.User;
import org.uv.tutor.models.UserInRoom;
import org.uv.tutor.models.Wrapper;
import org.uv.tutor.models.WrapperSettings;
import org.uv.tutor.repositories.UserInRoomRepository;
import org.uv.tutor.schema.MessageSchema;
import org.uv.tutor.schema.ProblemResponseSchema;
import org.uv.tutor.schema.ReportSchema;
import org.uv.tutor.services.LogService;
import org.uv.tutor.services.RealizedProblemService;
import org.uv.tutor.services.ReportService;
import org.uv.tutor.services.SolutionProgressDataService;
import org.uv.tutor.services.UserInRoomService;
import org.uv.tutor.services.UserService;
import org.uv.tutor.services.WaitroomService;
import org.uv.tutor.services.WrapperService;

import jakarta.validation.Valid;
import lombok.val;

/**
 * Controller for the conversation flow. When a user sends a message, this
 * controller receives the message and process it. The flow is as follows:
 * <ul>
 * <li>the user posts to /chat/join/{wrapperId}</li>
 * <li>the user receives from the scheduler component the $roomId$</li>
 * <li>At least one of the user in the group does the first call to GET
 * /chat/{roomId} this calls to the method startUserProblem</li>
 * <li>Whenever a participant sends a message, they send a call to PUT
 * /chat/{roomId} which calls to the method receiveMessage</li>
 * <li>If the problem requires a report, this report is sent by a call to POST
 * /chat/{roomId}/reports which calls the method addReport</li>
 * <li>The next problem in the collection is retrieved by making a call to GET
 * /chat/{roomId}</li>
 * <li>Finally, when the session is finished, a call is issued to DELETE
 * /chat/roomId</li>
 * 
 * </ul>
 * 
 * @author Pablo Arnau, Sergi Solera, Pedro J Canovas
 */
@Component
@RestController
@RequestMapping("/chat")
public class ChatController {
    Logger logger = LoggerFactory.getLogger(ChatController.class);
    private static final Locale LOCALE_SPANISH = new Locale("es", "ES"); // manually created since there no
                                                                         // Locale.SPANISH constants to check
    // private WebFlux webFlux = new Web

    private SolutionProgressDataService solutionProgressDataService;
    private RasaChatBotProxy chatBotProxy;
    private WaitroomService waitroomService;
    private UserService userService;
    private WrapperService wrapperService;
    private ProblemAdapter adapter;
    private Language language;
    private RealizedProblemService realizedProblemService;
    private SimpMessagingTemplate messagingTemplate;
    private RedissonClient redisson;
    private UserInRoomRepository userInRoomRepository;
    private TutoringPanelController tutoringPanelController;
    private ReportService reportService;
    private UserInRoomService userInRoomService;
    @Autowired
    private LogService logService;

    public ChatController(SolutionProgressDataService solutionProgressDataService, RasaChatBotProxy chatBotProxy,
            WaitroomService waitroomService, UserService userService, WrapperService wrapperService,
            ProblemAdapter adapter, Language language,
            RealizedProblemService realizedProblemService, SimpMessagingTemplate messagingTemplate,
            RedissonClient redisson, UserInRoomRepository userInRoomRepository,
            TutoringPanelController tutoringPanelController, ReportService reportService,
            UserInRoomService userInRoomService, LogService logService) {
        this.solutionProgressDataService = solutionProgressDataService;
        this.chatBotProxy = chatBotProxy;
        this.waitroomService = waitroomService;
        this.userService = userService;
        this.wrapperService = wrapperService;
        this.adapter = adapter;
        this.language = language;
        this.realizedProblemService = realizedProblemService;
        this.messagingTemplate = messagingTemplate;
        this.redisson = redisson;
        this.userInRoomRepository = userInRoomRepository;
        this.tutoringPanelController = tutoringPanelController;
        this.reportService = reportService;
        this.userInRoomService = userInRoomService;
        this.logService = logService;
    }

    @DeleteMapping("/all")
    void deleteAll() {
        solutionProgressDataService.deleteAll();
        userInRoomRepository.deleteAll();
        logService.deleteAll();
    }

  
    /**
     * Retrieves or initializes a problem for a user in a specific room. If 'next'
     * is true, it attempts to start the next problem in the sequence, in this case
     * we have to check if the number of reports in the room is not less than the
     * total users, if not it throws an unauthorized error. If the conditions are
     * met, it sends the problem to all the users subscribed to the session's topic.
     *
     * @param roomId     the unique identifier of the room
     * @param wrapperId  the identifier for the wrapper of the problem
     * @param exerciseId the identifier of the exercise
     * @param next       indicate if the next problem should be initiated
     * @param principal  principal object containing the information about the
     *                   currently authenticated user
     * @return a ProblemResponseSchema object representing the problem for the user
     */

    @GetMapping("{roomId}")
    ProblemResponseSchema startUserProblem(
            @PathVariable("roomId") String roomId,
            @RequestParam(value = "wrapperId", required = true) Long wrapperId,
            @RequestParam(value = "exerciseId", required = true) Integer exerciseId,
            Principal principal) {
        val lock = redisson.getFairLock("lock-" + roomId);
        ProblemResponseSchema problemResponseSchema = new ProblemResponseSchema();
        if (!userInRoomRepository.existsByRoomName(roomId))
            return null;

        try {
            lock.tryLock(3, 2, TimeUnit.SECONDS);
            var userSession = solutionProgressDataService.load(roomId);

            if (userSession == null) {
                userSession = createNewChatRoom(roomId, wrapperId, exerciseId, principal.getName(), principal);
            }

            problemResponseSchema = ProblemResponseSchema.buildFromUserSession(userSession, tutoringPanelController,
                    wrapperService, logService, roomId);
            messagingTemplate.convertAndSend("/topic/room-" + roomId, problemResponseSchema);
            logger.info("Post-send");
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (Exception e1) {
            e1.printStackTrace();
        } finally {
            lock.unlock();
        }

        return problemResponseSchema;
    }

    /**
     * Adds a new report to a specified room. It creates a new report based on the
     * provided data, saves it, and, under certain conditions (like matching report
     * and user counts), create the new chatRoom for the next problem in the
     * collection and sends the next problem to all the users subscribed to the
     * session's topic.
     *
     * @param principal principal object containing the information about the
     *                  currently authenticated user
     * @param roomId    the identifier of the room to which the report is added
     * @param rep       the report data encapsulated in a ReportSchema object
     */

    @PostMapping("/{roomId}/reports")
    public void addReport(Principal principal, @PathVariable String roomId, @RequestBody @Valid ReportSchema rep) {
        var userSession = solutionProgressDataService.load(roomId);
        Report report = new Report();
        ProblemResponseSchema problemResponseSchema = new ProblemResponseSchema();
        val lock = redisson.getFairLock("lock-" + roomId);

        try {
            lock.tryLock(3, 2, TimeUnit.SECONDS);
            report.setUser(userService.getByUserName(principal.getName()));
            report.setProblem(realizedProblemService.get(rep.getProblem()));
            report.setWrapper(wrapperService.get(rep.getWrapper()));
            report.setReportType(rep.getReportType());
            report.setResults(rep.getResults());
            report.setRoomId(rep.getRoomId());

            reportService.save(report);

            if (userSession.getWrapper().getSettings().getSelectionMode().getSelectionMode() != "User") {
                int samReportsInRoom = reportService.getTestReports(roomId, rep.getProblem());
                int totalUsersInRoom = userInRoomService.getTotalUsersByRoomId(roomId);
                if (samReportsInRoom == totalUsersInRoom
                        && rep.getProblem() < wrapperService.get(rep.getWrapper()).getProblems().size()) {
                    userSession = createNewChatRoom(roomId, rep.getWrapper(), rep.getProblem().intValue(),
                            principal.getName(),
                            principal);
                    problemResponseSchema = ProblemResponseSchema.buildFromUserSession(userSession,
                            tutoringPanelController, wrapperService, logService, roomId);
                    messagingTemplate.convertAndSend("/topic/room-" + roomId, problemResponseSchema);
                }
            }
        } catch (InterruptedException e) {
            logger.error("", e);
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves the room identifier associated with the currently authenticated
     * user. This roomId is used as the identifier of the room in the rest of the
     * methods
     *
     * @param principal principal object containing the information about the
     *                  currently authenticated user
     * @return the identifier of the room associated with the current user
     * @throws NotFoundException if the user is not found in the room repository
     */
    @GetMapping("/roomId")
    String getRoomId(Principal principal) {
        UserInRoom user = userInRoomRepository.getByUsername(principal.getName());
        if (user == null) {
            throw new NotFoundException();
        }
        return user.getRoomName();
    }


    /**
     * Processes a message received for a specific room, updating the session
     * accordingly. If a timeout is indicated, it marks the problem as
     * solved by a timed out and updates the session. It processes the
     * incoming message, updates the session, and constructs a ProblemResponseSchema
     * object based on the updated session. The method sends this
     * response to subscribed users unless a timeout occurred.
     *
     * @param messageSchema the schema containing the message to be processed
     * @param roomId        the identifier of the room for which the message
     *                      is intended
     * @param timeOut       indicate if the problem is finish by a timed out
     * @param principal     principal object containing the information about the
     *                      currently authenticated user
     * @return a ProblemResponseSchema object representing the current state after
     *         message processing
     * @throws NotFoundException if necessary data for message processing is not
     *                           found
     */
    @PutMapping("/{roomId}")
    public ProblemResponseSchema receiveMessage(@RequestBody @Valid MessageSchema messageSchema,
            @PathVariable("roomId") String roomId,
            Principal principal) throws NotFoundException {
        val timeout = timeOut != null && timeOut;
        ProblemResponseSchema response = new ProblemResponseSchema();

        val lock = redisson.getFairLock("lock-" + roomId);

        try {
            lock.tryLock(3, 2, TimeUnit.SECONDS);
            val userSession = processIncomingMessage(roomId, messageSchema.getMessage(), principal.getName());
            response = ProblemResponseSchema.buildFromUserSession(userSession, tutoringPanelController, wrapperService,
                    logService, roomId);
        } catch (InterruptedException ex) {
            logger.info(
                    "interrupted while waiting for processing, if this problem persists, consider increasing the processing time");
            lock.unlock();
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }

        return response;
    }

    /**
     * Allows a user to join a waitroom associated with a specific wrapper ID.
     * Returns the output of the addToWaitRoom function from waitRoomService. This
     * will either be the roomId if the user has joined an existing room, or null if
     * the room is not yet created. In the case where a room needs to be created,
     * the addToWaitRoom function is responsible for requesting a new room for the
     * user. This process is managed within the Scheduling.java file.
     *
     * @param wrapperId the identifier of the wrapper associated with the waitroom
     * @param principal principal object containing the information about the
     *                  currently authenticated user
     * @return the identifier of the room that the user has joined
     */
    @PostMapping("/join/{wrapperId}")
    public String joinWaitroom(@PathVariable("wrapperId") Long wrapperId, Principal principal) {
        val lock = redisson.getFairLock("lock-user-" + principal.getName() + "-subscription");
        String room = null;
        String username = principal.getName();
        User user = userService.findByUsername(username);

        try {
            Wrapper wrapper = wrapperService.findById(wrapperId);
            lock.tryLock(3, 2, TimeUnit.SECONDS);
            Optional<UserInRoom> userInRoom = userInRoomService.getUserInRoom(principal.getName(), wrapper.getName());
            if (userInRoom.isPresent()) {
                room = userInRoom.get().getRoomName();
            } else { // create waitroom or restore expire time
                room = waitroomService.setUserWaitRoom(user, wrapper);
            }

        } catch (Exception ex) {
            logger.error("ERROR IN WAITROOM SERVICE LOCK ACQUISITION", ex);
        } finally {
            lock.unlock();
        }
        return room;
    }

    /**
     * Creates a new chat room for a user interaction with a problem. It initializes
     * the room with specific problem and user settings.
     * The function retrieves user and wrapper data, selects the appropriate problem
     * based on the exercise ID, and constructs a user model.
     * It calculates the problem's maximum resolution time and prepares the session
     * data. After initializing the session, it saves it and
     * returns the newly created SolutionProgressData object. The method also sets
     * up initial messages for chatbot interaction in the session.
     *
     * @param roomId     the identifier for the new chat room
     * @param wrapperId  the wrapper ID associated with the user
     * @param exerciseId the exercise ID for the problem to be loaded in the chat
     *                   room
     * @param username   the username of the user who is creating the room
     * @param principal  principal object containing the information about the
     *                   currently authenticated user
     * @return a SolutionProgressData object representing the newly created chat
     *         room session
     */
    private SolutionProgressData createNewChatRoom(String roomId, Long wrapperId, Integer exerciseId, String username,
            Principal principal) {
        val user = userService.findByUsername(username);
        val wrapper = wrapperService.getByUser(wrapperId, user);
        val locale = Locale.forLanguageTag(user.getLanguage());

        val problemEntity = wrapper.getProblemsCollections().stream()
                .filter(pc -> pc.getOrderNumber() == exerciseId.intValue()).findFirst()
                .map(ProblemsCollections::getProblem)
                .orElseThrow(NotFoundException::new);
        val problem = adapter.fromModelProblem(principal, problemEntity, wrapper.getSettings().getHelpLevel());
        val users = userInRoomService.getAllByRoomName(roomId);

        val userModel = new UserModelProxy(users.stream().map(User::getUsername).map(u -> new UserModel(u)).toList());
        userModel.setCurrentUser(username);
        userModel.setLang(user.getLanguage());

        LocalDateTime horaActual = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime horaFinProblema = horaActual.plus(Duration.ofSeconds(problem.getMaxResolutionTime() + 1));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String maxResolutionTimeFormatedString = horaFinProblema.format(formatter);

        val realizedProblem = realizedProblemService.save(RealizedProblem.builder()
                .problem(problemEntity)
                .date(new Date())
                .finishByTimer(false)
                .build());
        val userData = new SolutionProgressData(exerciseId.intValue(), problem, userModel, wrapper,
                realizedProblem, maxResolutionTimeFormatedString, locale, language);
        chatBotProxy.addInitMessage("", problem, wrapper, locale, userData.getMessages());
        solutionProgressDataService.save(roomId, userData);
        return userData;
    }

    /**
     * Processes an incoming message in the context of a specific chat room. The
     * function loads the user session associated with the room, updates the timeout
     * status and the current user, and sets the language of the session.
     * Interaction with the chatbot is handled through the retrieval of the chatbot
     * proxy state and the sending of the message. After processing the chatbot's
     * response, the updated session state is saved and returned.
     * 
     * @param roomId   the identifier of the chat room
     * @param message  the received message to process
     * @param username the username of the message sender
     * @param timeOut  indicates if the problem has ended due to a timeout
     * @return the updated progress session after processing the
     *         message
     */
    private SolutionProgressData processIncomingMessage(String roomId, String message, String username,
            Boolean timeOut, Boolean skipProblem) {
        val userSession = solutionProgressDataService.load(roomId);
        if (!userSession.getUserModel().isUserPresent(username))
            userSession.getUserModel().addUserModel(new UserModel(username));
        userSession.setTimeOut(timeOut);
        userSession.setSkipProblem(skipProblem);
        userSession.getUserModel().setCurrentUser(username);
        userSession.setRoomId(roomId);

        this.chatBotProxy.sendMessage(message, userSession);
        solutionProgressDataService.save(roomId, userSession);
        return userSession;
    }
}
