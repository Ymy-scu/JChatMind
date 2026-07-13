package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chunk_bge_m3】的数据库操作Mapper
 * @createDate 2025-12-02 15:44:34
 * @Entity com.kama.jchatmind.model.entity.ChunkBgeM3
 */
@Mapper
public interface ChunkBgeM3Mapper {
    int insert(ChunkBgeM3 chunkBgeM3);

    ChunkBgeM3 selectById(String id);

    int deleteById(String id);

    int updateById(ChunkBgeM3 chunkBgeM3);

    /**
     * 向量召回（cosine 距离），返回按相似度降序的前 {@code limit} 条 chunk。
     *
     * <p>SQL 使用 {@code embedding <=> :query::vector} 计算 cosine 距离，
     * 相似度 = {@code 1 - distance}。此方法应配合上层阈值过滤使用。</p>
     */
    List<ChunkBgeM3> similaritySearch(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    /**
     * BM25 关键词召回，基于 {@code content_tsv} 生成列 + GIN 索引。
     *
     * <p>使用 {@code plainto_tsquery('simple', :query)} 生成查询，
     * {@code ts_rank_cd} 打分。适合专有名词、代码符号、缩写等场景。</p>
     */
    List<ChunkBgeM3> bm25Search(
            @Param("kbId") String kbId,
            @Param("query") String query,
            @Param("limit") int limit
    );

    List<ChunkBgeM3> selectByDocId(@Param("docId") String docId);

    int deleteByDocId(@Param("docId") String docId);

    /**
     * 分页扫描：按 {@code id} 升序返回下一页 chunk（{@code id > lastId}）。
     *
     * <p>供历史数据回填 runner 使用（{@code embed(title)} → {@code embed(content)}），
     * 用 {@code lastId} 做游标，避免 OFFSET 深分页开销。传入 {@code null} 从头开始。</p>
     */
    List<ChunkBgeM3> selectPageAfter(
            @Param("lastId") String lastId,
            @Param("limit") int limit
    );

    /** 全表 chunk 计数，用于回填 runner 打印进度百分比。 */
    long countAll();
}
