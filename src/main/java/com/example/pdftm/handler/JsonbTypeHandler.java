package com.example.pdftm.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 把 JSONB 列与 Jackson JsonNode 互转。
 *
 * 全局注册方式：在 application.yml 里配置 mybatis.type-handlers-package=com.example.pdftm.handler，
 * 配合下面的 @MappedTypes(JsonNode.class) + @MappedJdbcTypes(JdbcType.OTHER, includeNullJdbcType=true)，
 * 自动映射场景下 mybatis 会自己挑到这个 handler。
 *
 * 显式声明（XML mapper 里的标准用法，避免依赖隐式选择）：
 *   - resultMap：&lt;result column="model" property="model"
 *                  typeHandler="com.example.pdftm.handler.JsonbTypeHandler"/&gt;
 *   - 写入参数：#{model,jdbcType=OTHER,typeHandler=com.example.pdftm.handler.JsonbTypeHandler}
 *
 * 注意：
 *  - PGobject 来自 openGauss-jdbc / postgresql 驱动，两者都 export 这个类。
 *  - 写入时 type 必须是 "jsonb"，否则 GaussDB 会按字符串存到 jsonb 列里
 *    引发 "column ... is of type jsonb but expression is of type text" 错误。
 */
@MappedTypes(JsonNode.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class JsonbTypeHandler extends BaseTypeHandler<JsonNode> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, JsonNode parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        try {
            pg.setValue(MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("Failed to serialize JsonNode -> JSONB", e);
        }
        ps.setObject(i, pg);
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private JsonNode parse(String raw) throws SQLException {
        if (raw == null) return null;
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new SQLException("Failed to parse JSONB -> JsonNode: " + raw, e);
        }
    }
}
