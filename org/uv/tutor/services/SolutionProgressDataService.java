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
     * Deletes an object from the redisDB
     * 
     * @param sessionId the key of the object to delete
     */
    public void delete(String sessionId) {
        keyCommands.del(sessionId.getBytes());
    }

    public void deleteAll() {
        Set<byte[]> allKeys = keyCommands.keys("*".getBytes());
        if (allKeys != null) {
            byte[][] keySet = allKeys.toArray(new byte[allKeys.size()][]);
            keyCommands.del(keySet);
        }
    }

    /**
     * Saves in Redis
     * 
     * @param key         key under to store the object
     * @param userSession the object to save
     */
    public void save(String key, SolutionProgressData userSession) {
        SolutionProgressData data = new SolutionProgressData(userSession);
        data.setWrapperId(data.getWrapper().getId());
        data.setWrapper(null);
        data.setRealizedProblemId(data.getRealizedProblem().getId());
        data.setRealizedProblem(null);
        val serialized = serializer.toBytes(data);
        stringCommands.set(key.getBytes(), serialized);
    }

    public SolutionProgressData load(String sessionId) {
        logger.info("Getting redis session on {} ", sessionId);
        val bytes = stringCommands.get(sessionId.getBytes());
        if (bytes == null) {
            return null;
        }
        val userSession = serializer.fromBytes(bytes);
        if (userSession == null) {
            return null;
        }

        userSession.setWrapper(wrapperService.get(userSession.getWrapperId()));
        userSession.setRealizedProblem(realizedProblemService.get(userSession.getRealizedProblemId()));
        userSession.setLocalizedLanguage(new LocalizedLanguage(language, userSession.getLocale()));

        userSession.setTemporaryHashmap(new HashMap<>());
        return userSession;
    }

}
