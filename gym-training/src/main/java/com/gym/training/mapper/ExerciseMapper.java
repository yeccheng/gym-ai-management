package com.gym.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.training.entity.Exercise;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ExerciseMapper extends BaseMapper<Exercise> {

    /**
     * 口语化动作名召回。
     *
     * <p>智能体的工具入参来自大模型的自然语言理解，它给出的几乎不会是库里的标准名：
     * 用户说「卧推」，库里是「杠铃卧推」；说「飞鸟」，库里是「哑铃飞鸟」。
     * 工具层如果拿这个名字去等值匹配，必然查不到，模型就会以为是用户没练过。
     *
     * <p>这里同时匹配标准名、英文名、别名三个字段。
     * 别名字段存在的唯一意义就是承接这种口语输入——
     * 建库时给它填上「卧推/推胸/平板卧推」，召回才立得住。
     */
    @Select("""
            SELECT e.* FROM exercise e
            WHERE e.deleted = 0
              AND (e.name     LIKE CONCAT('%', #{keyword}, '%')
                OR e.name_en  LIKE CONCAT('%', #{keyword}, '%')
                OR e.alias    LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY COALESCE(e.action_level, 2) ASC,
                     (CASE WHEN e.name = #{keyword} THEN 0 ELSE 1 END),
                     LENGTH(e.name) ASC
            LIMIT #{limit}
            """)
    List<Exercise> searchByName(@Param("keyword") String keyword, @Param("limit") int limit);

    /**
     * 按标准名精确取一条。
     * 演示数据入库、计划模板这类场景需要的是确定性，模糊召回在这里是负担。
     */
    @Select("SELECT e.* FROM exercise e WHERE e.deleted = 0 AND e.name = #{name} LIMIT 1")
    Exercise selectByName(@Param("name") String name);

    /**
     * 按器材标签 + 关键词检索动作库。
     * tag 与 equipment 字段映射：free=杠铃/壶铃，machine=固定器械/绳索，
     * dumbbell=哑铃，bodyweight=自重。空 tag 表示不过滤器材。
     */
    @Select("""
            <script>
            SELECT e.* FROM exercise e
            WHERE e.deleted = 0
            <if test="equipments != null and equipments.size() &gt; 0">
              AND e.equipment IN
              <foreach collection="equipments" item="eq" open="(" separator="," close=")">
                #{eq}
              </foreach>
            </if>
            <if test="keyword != null and keyword != ''">
              AND (e.name LIKE CONCAT('%', #{keyword}, '%')
                OR e.name_en LIKE CONCAT('%', #{keyword}, '%')
                OR e.alias LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY e.primary_muscle, e.name
            LIMIT #{limit}
            </script>
            """)
    List<Exercise> searchByTag(@Param("equipments") List<Integer> equipments,
                               @Param("keyword") String keyword,
                               @Param("limit") int limit);
}
