package org.uv.tutor.controllers;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.CrossOrigin;
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
import org.uv.tutor.core.chat.ChatBotProxy;
import org.uv.tutor.core.exceptions.NotFoundException;
import org.uv.tutor.core.modelling.UserModel;
import org.uv.tutor.core.modelling.UserModelProxy;
import org.uv.tutor.core.structure.adapters.ProblemAdapter;
import org.uv.tutor.models.EGroupStrategy;
import org.uv.tutor.models.RealizedProblem;
import org.uv.tutor.models.Report;
import org.uv.tutor.models.User;
import org.uv.tutor.models.UserInRoom;
import org.uv.tutor.models.Wrapper;
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.val;

@Component
@RestController
@RequestMapping("/chat-session")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ChatController {
    Logger logger = LoggerFactory.getLogger(ChatControllerSession.class);

    private SolutionProgressDataService solutionProgressDataService;
    private ChatBotProxy chatBotProxy;
    private WaitroomService waitroomService;
    private UserService userService;
    private WrapperService wrapperService;
    private ProblemAdapter adapter;
    private Language language;
    private RealizedProblemService realizedProblemService;
    private UserInRoomRepository userInRoomRepository;
    private TutoringPanelController tutoringPanelController;
    private ReportService reportService;
    private UserInRoomService userInRoomService;
    private LogService logService;

    public ChatControllerSession(SolutionProgressDataService solutionProgressDataService, ChatBotProxy chatBotProxy,
            WaitroomService waitroomService, UserService userService, WrapperService wrapperService,
            ProblemAdapter adapter, Language language,
            RealizedProblemService realizedProblemService,
            UserInRoomRepository userInRoomRepository,
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
        this.tutoringPanelController = tutoringPanelController;
        this.reportService = reportService;
        this.userInRoomRepository = userInRoomRepository;
        this.userInRoomService = userInRoomService;
        this.logService = logService;
    }

    @GetMapping("{roomId}")
    ProblemResponseSchema startUserProblem(
            @PathVariable("roomId") String roomId,
            @RequestParam(value = "wrapperId", required = true) Long wrapperId,
            @RequestParam(value = "exerciseId", required = true) Integer exerciseId,
            HttpServletRequest request,
            Principal principal) {
        ProblemResponseSchema problemResponseSchema;
        if (!userInRoomRepository.existsByRoomName(roomId))
            return null;

        var userSession = solutionProgressDataService.load(roomId, request.getSession());

        if (userSession == null) {
            userSession = createNewChatRoom(roomId, wrapperId, exerciseId, principal.getName(), principal, request);
        }

        problemResponseSchema = ProblemResponseSchema.buildFromUserSession(userSession, tutoringPanelController,
                wrapperService, logService, roomId);

        return problemResponseSchema;
    }

    @GetMapping("/roomId")
    String getRoomId(Principal principal) {
        UserInRoom user = userInRoomRepository.getByUsername(principal.getName());
        if (user == null) {
            throw new NotFoundException();
        }
        return user.getRoomName();
    }

    @PutMapping("/{roomId}")
    public ProblemResponseSchema receiveMessage(@RequestBody @Valid MessageSchema messageSchema,
            @PathVariable("roomId") String roomId,
            Principal principal, HttpServletRequest request) throws NotFoundException {
        ProblemResponseSchema response = new ProblemResponseSchema();
        ProblemResponseSchema responseTimeOut = new ProblemResponseSchema();

        val userSession = processIncomingMessage(roomId, messageSchema.getMessage(), principal.getName(), timeOut,
                request);
        response = ProblemResponseSchema.buildFromUserSession(userSession, tutoringPanelController, wrapperService,
                logService, roomId);

        return response;
    }

    @PostMapping("/join/{wrapperId}")
    public String joinWaitroom(@PathVariable("wrapperId") Long wrapperId, Principal principal) {
        String room = null;
        String username = principal.getName();
        User user = userService.findByUsername(username);
        Wrapper wrapper = wrapperService.findById(wrapperId);
        String userInRoom = userInRoomService.getUserInRoom(principal.getName(), wrapper.getName()); // Returns a random
                                                                                                     // UUID
        return userInRoom;
    }

    private SolutionProgressData createNewChatRoom(String roomId, Long wrapperId, Integer exerciseId, String username,
            Principal principal, HttpServletRequest request) {
        val user = userService.findByUsername(username);
        val wrapper = wrapperService.getByUser(wrapperId, user);
        val locale = Locale.forLanguageTag(user.getLanguage());
        val problemEntity = wrapper.getProblems().get(exerciseId);
        val problem = adapter.fromModelProblem(principal, problemEntity, wrapper.getSettings().getHelpLevel());

        val userModel = new UserModel(user.getUsername());

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
        chatBotProxy.addInitMessage("👋🏼", problem, wrapper, userData.getLocale(), userData.getMessages()); // if
                                                                                                             // working
                                                                                                             // in group
                                                                                                             // substitute
        // by guys or smthing
        solutionProgressDataService.save(roomId, userData, request.getSession());
        return userData;
    }

    private SolutionProgressData processIncomingMessage(String roomId, String message, String username,
            Boolean timeOut, HttpServletRequest request) {
        val userSession = solutionProgressDataService.load(roomId, request.getSession());
        userSession.setTimeOut(timeOut);
        userSession.getUserModel().setCurrentUser(username);
        userSession.setRoomId(roomId);

        this.chatBotProxy.sendMessage(message, userSession);
        solutionProgressDataService.save(roomId, userSession, request.getSession());
        return userSession;
    }
}
