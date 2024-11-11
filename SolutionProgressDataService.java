package org.uv.tutor.services;

import java.util.HashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.stereotype.Service;
import org.uv.legacy.tutor.model.Language;
import org.uv.legacy.tutor.model.LocalizedLanguage;
import org.uv.legacy.tutor.model.SolutionProgressData;
import org.uv.tutor.serialization.ObjectToBytesSerializer;

import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import lombok.val;

@Service
@AllArgsConstructor
public class SolutionProgressDataService {

    private WrapperService wrapperService;
    private RealizedProblemService realizedProblemService;
    private ObjectToBytesSerializer<SolutionProgressData> serializer;
    private RedisKeyCommands keyCommands;
    private RedisStringCommands stringCommands;
    private Language language;

    private static final Logger logger = LoggerFactory.getLogger(SolutionProgressDataService.class);

    /**
     * Saves in the local http session
     * SHOULD BE DELETED WHEN PERFORMANCE EXPERIMENTS ARE FINISHED @see
     * {@link org.uv.tutor.controllers.ChatControllerSession}
     * 
     * @param key
     * @param userSession
     * @param session     a reference to the local httpsession engine
     */
    public void save(String key, SolutionProgressData userSession, HttpSession session) {
        SolutionProgressData data = new SolutionProgressData(userSession);
        data.setWrapperId(data.getWrapper().getId());
        data.setWrapper(null);
        data.setRealizedProblemId(data.getRealizedProblem().getId());
        data.setRealizedProblem(null);
        byte[] serialized = serializer.toBytes(data);
        session.setAttribute("CSSO", serialized);
    }

    /**
     * Retrieves the SolutionProgressData from the internal session storage.
     * SHOULD BE DELETED WHEN PERFORMANCE EXPERIMENTS ARE FINISHED @see
     * {@link org.uv.tutor.controllers.ChatControllerSession}
     * 
     * @param sessionId
     * @param session
     * @return
     * 
     */
    public SolutionProgressData load(String sessionId, HttpSession session) {
        val bytes = (byte[]) session.getAttribute("CSSO");
        if (bytes == null) {
            return null;
        }
        val userSession = serializer.fromBytes(bytes);

        userSession.setWrapper(wrapperService.get(userSession.getWrapperId()));
        userSession.setRealizedProblem(realizedProblemService.get(userSession.getRealizedProblemId()));
        userSession.setLocalizedLanguage(new LocalizedLanguage(language, userSession.getLocale()));
        userSession.setTemporaryHashmap(new HashMap<>());

        return userSession;
    }

}
