package com.aiknowledge.message.mapper;

import com.aiknowledge.message.entity.ChatMessageRemovalEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;

public interface ChatMessageRemovalMapper extends BaseMapper<ChatMessageRemovalEntity> {
    /** Records "cleared through the newest message" for each listed conversation that has messages. */
    @Insert("<script>INSERT INTO chat_message_removal (session_id, cleared_through_id, created_at) "
            + "SELECT session_id, MAX(id), #{now} FROM chat_message WHERE session_id IN "
            + "<foreach collection='sessionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY session_id</script>")
    int recordClears(@Param("sessionIds") Collection<Long> sessionIds, @Param("now") LocalDateTime now);

    /** The same for every conversation (an admin clearing all messages). */
    @Insert("INSERT INTO chat_message_removal (session_id, cleared_through_id, created_at) "
            + "SELECT session_id, MAX(id), #{now} FROM chat_message GROUP BY session_id")
    int recordAllClears(@Param("now") LocalDateTime now);

    @Delete("DELETE FROM chat_message_removal WHERE created_at < #{before} LIMIT 1000")
    int pruneBefore(@Param("before") LocalDateTime before);
}
