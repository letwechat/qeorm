package qeorm;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cglib.beans.BeanMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import qeorm.intercept.IFunIntercept;
import qeorm.utils.ExtendUtils;
import qeorm.utils.JsonUtils;
import qeorm.utils.Models;
import qeorm.utils.Wrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Created by ashen on 2017-2-4.
 */
public class SqlResultExecutor {

    private static SqlSession sqlSession;

    public static void setSqlSession(SqlSession _sqlSession) {
        sqlSession = _sqlSession;
    }

    private Logger logger = LoggerFactory.getLogger(SqlResultExecutor.class);
    SqlResult result;
    QePage qePage;
    Map<String, Object> oParams;


    public SqlResult getResult() {
        return result;
    }

    public SqlResultExecutor() {
    }

    public SqlResultExecutor init(SqlConfig sqlConfig, Map<String, Object> map) {
        qePage = new QePage();
        oParams = cloneMap(map);
        result = new SqlResult();
        result.setSqlConfig(sqlConfig);
        result.setParams(cloneMap(map));
        dealParamIntercepts();
        return this;
    }

    public SqlResult exec() {
        String sql = result.getSqlConfig().getSql();
        TimeWatcher.watch("生成sql:" + sql, new Action() {
            @Override
            public void apply() {
                if (sql.matches(SqlConfigManager.isInsertPattern)) {
                    createInsertSql();
                } else {
                    createSelectSql();
                }
            }
        });

        TimeWatcher.watch("在数据库" + result.sqlConfig.getDbName() + "上执行" + result.getSql(), new Action() {
            @Override
            public void apply() {
                result.setResult(exec(result.getParams()));
            }
        });
        if (!result.sqlConfig.isPrimitive()) dealFunIntercept(result.getResult());
        if (result.getResult() != null) {
            if (oParams != null && oParams.containsKey("pn"))
                oParams.remove("pn");
        }
        dealSqlIntercepts();
        if (result.getResult() != null && !result.sqlConfig.isPrimitive())
            dealReturnType();
        else if (result.getResult() != null)
            result.setResult(JsonUtils.convert(result.getResult(), result.getSqlConfig().getKlass()));
        dealQePage();
        return result;
    }

    public <T> T exec(Map<String, Object> map) {
        String sql = result.getSql();
        String sqlType = result.getSqlConfig().getSqlType();
        logger.info("要在数据库{}上执行的sql：{} , 参数为：{}", result.sqlConfig.getDbName(), sql, JsonUtils.toJson(map));
        NamedParameterJdbcOperations jdbc = sqlSession.getJdbcTemplate(this.result.sqlConfig.getDbName());
        if (sqlType.equals(SqlConfig.CURSOR)) {
            return (T) jdbc.query(sql, map, new RowCallbackHandlerResultSetExtractor(result.getSqlConfig().getRowCallbacks(), result));
        } else if (sqlType.equals(SqlConfig.COUNT)) {
            return (T) jdbc.queryForObject(sql, map, Long.class);
        } else if (sqlType.equals(SqlConfig.UPDATE) || sqlType.equals(SqlConfig.DELETE)) {
            Object ret = jdbc.update(sql, map);
            CacheManager.instance.edit(result.getSqlConfig().getTableNameList());
            return (T) ret;
        } else if (sqlType.equals(SqlConfig.INSERT)) {
            KeyHolder keyholder = new GeneratedKeyHolder();
            jdbc.update(sql, new MapSqlParameterSource(map), keyholder);
            if (keyholder != null && keyholder.getKey() != null) {
                Object ret = keyholder.getKey().longValue();
                CacheManager.instance.edit(result.getSqlConfig().getTableNameList());
                return (T) ret;
            } else {
                return null;
            }

        } else if (result.sqlConfig.isPrimitive())
            return (T) jdbc.queryForObject(sql, map, result.getSqlConfig().getKlass());
        else if (sqlType.equals(SqlConfig.SELECT)) {
            return (T) jdbc.queryForList(sql, map);
        } else {
            return (T) jdbc.queryForList(sql, map);
        }
    }

    public void dealQePage() {
        Object ret = result.getResult();
        if (ret instanceof QePage) {
            qePage.setTotal(((QePage) ret).getTotal());
            qePage.addAll((List) ret);
            result.setResult(qePage);
        } else if (ret instanceof List) {
            if (qePage.isCount()) {
                NamedParameterJdbcOperations jdbc = sqlSession.getJdbcTemplate(this.result.sqlConfig.getDbName());
                qePage.setTotal(jdbc.queryForObject(qePage.getCountSql(), result.getParams(), Long.class));
            }

            qePage.addAll((List) ret);
            result.setResult(qePage);
        }
    }

    private void createSelectSql() {
        String sql = result.getSqlConfig().getSql();
        Map<String, Object> map = result.getParams();
        for (SqlAnalysisNode node : result.getSqlConfig().getGroup()) {
            String temp = "";
            if (map.containsKey(node.getParam())) {
                if (node.isBy()) {
                    if (result.getSqlConfig().getSqlType().equals(SqlConfig.SELECT)) temp = node.getWhole().replace(
                            node.getParamWhole(),
                            String.valueOf(map.get(node.getParam())).replaceAll("'", "\\'"));
                    else
                        temp = " ";
                } else if (node.isLike()) {
                    temp = node.getWhole().replace(
                            node.getPrefix() + node.getParamWhole() + node.getSuffix(),
                            ":" + node.getParam());
                    map.put(node.getParam(),
                            node.getPrefix().replaceAll("'", "")
                                    + map.get(node.getParam())
                                    + node.getSuffix().replaceAll("'", ""));
                } else if (node.isIn() || node.isNotIn()) {
                    Object val = map.get(node.getParam());
                    Object[] vs = null;
                    if (val != null) {
                        if (val instanceof String) {
                            Collection t = Splitter.on(",").splitToList(String.valueOf(val));
                            vs = Iterators.toArray(t.iterator(), Object.class);
                        } else if (val instanceof Iterable) {
                            Iterable t = (Iterable) val;
                            vs = Iterators.toArray(t.iterator(), Object.class);
                        } else if (val.getClass().isArray()) {
                            vs = (Object[]) val;
                        } else {
                            vs = new Object[]{val};
                        }

                    }
                    if (vs != null) {
                        List<String> ps = Lists.newArrayList();
                        String paramName = node.getParam();
                        String key;
                        for (int l = vs.length, i = 0; i < l; i++) {
                            key = paramName + "_" + i;
                            ps.add(":" + key);
                            map.put(key, vs[i]);
                        }
                        String _ps = Joiner.on(",").join(ps);
                        temp = node.getWhole().replace(node.getParamWhole(), _ps);
                    } else {
                        temp = node.getWholePrefix() + " 1=1 ";
                    }
                } else {
                    if (map.get(node.getParam()) != null || result.getSqlConfig().getSqlType().equals(SqlConfig.UPDATE)) {
                        temp = node.getWhole().replace(node.getParamWhole(), ":" + node.getParam());
                    } else {
                        temp = node.getWhole().replace(node.getOperator(), " is ").replace(node.getParamWhole(), "null");
                    }
                }
            } else {
                if (!node.isBy())
                    temp = node.getWholePrefix() + " 1=1 ";
            }
            sql = sql.replace(node.getWhole(), " " + temp + " ");
        }

        for (SqlAndOrNode node : result.getSqlConfig().getAndOrNodes()) {
            String temp = "";
            if (map.containsKey(node.getParam1()) && map.containsKey(node.getParam2())) {
                temp = node.getWhole().replace(node.getParamWhole1(), ":" + node.getParam1());
                temp = temp.replace(node.getParamWhole2(), ":" + node.getParam2());
            } else
                temp = " 1=1 ";
            sql = sql.replace(node.getWhole(), temp);
        }


        sql = replaceWhere(sql);

//        sql = sql.replaceAll("(?i)1=1\\s*or\\s+", " ");
//        sql = sql.replaceAll("\\(+\\s*1=1\\s*\\)", " 1=1 ");
//        sql = sql.replaceAll("(?i)and\\s*1=1\\s+", " ");
//        sql = sql.replaceAll("(?i)or\\s*1=1\\s+", " ");
//        sql = sql.replaceAll("\\(+\\s*1=1\\s*\\)", " 1=1 ");
////        sql = sql.replaceAll("(?i)count\\s*\\([^\\)]+\\s*\\)", " count(1) ");
//        //update
//        sql = sql.replaceAll(",\\s*1=1\\s*", " ");
//        sql = sql.replaceAll("1=1\\s*,", " ");

        sql = sql.replaceAll("\\s+", " ");
        sql = sql.replaceAll("\\s+\\(\\s+\\)", "()");
        sql = sql.replaceAll("\\(\\s+", "(");
        sql = sql.replaceAll("\\s+\\)", ")");
        sql = sql.replaceAll("(?i)\\s+where\\s+1=1\\s+", " ");
        String type = result.getSqlConfig().getSqlType();
        if ((type.equals(SqlConfig.UPDATE) || type.equals(SqlConfig.DELETE))
                && sql.toLowerCase().endsWith("where 1=1 ")) {
            throw new SqlErrorException("更新语句缺少条件，会造成全表跟新：" + sql);
        }
        if (result.getSqlConfig().getSqlType().equals(SqlConfig.SELECT) && map.containsKey("ps") && map.containsKey("pn") && map.get("ps") != null && map.get("pn") != null) {

            int pn = Integer.valueOf(map.get("pn").toString());
            int ps = Integer.valueOf(map.get("ps").toString());
            int start = ps * (pn - 1);

            boolean needCount = map.containsKey("needCount") && Boolean.parseBoolean(map.get("needCount").toString());
            qePage = new QePage(pn, ps, needCount, sql);

            sql = sql + " limit " + start + " , " + ps;
        }
        sql = StringFormat.format(sql, new AbstractRegexOperator() {
            @Override
            public String getPattern() {
                return "\\{([^\\}]+)\\}";
            }

            @Override
            public String exec(Matcher m) {
                return map.get(m.group(1)).toString();
            }
        });
        result.setSql(sql);
    }


    public String replaceWhere(String sql) {
        sql = sql.replaceAll(",\\s*1=1\\s*", " ");
        sql = sql.replaceAll("1=1\\s*,", " ");
        sql = sql.replaceAll("(?i)1=1\\s+or\\s+", " ");
        sql = sql.replaceAll("(?i)\\s+or\\s+1=1\\s*", " ");
        sql = sql.replaceAll("(?i)\\s+and\\s+1=1\\s*", " ");
        sql = sql.replaceAll("(?i)\\s+1=1\\s+and\\s*", " ");
        sql = sql.replaceAll("\\(+\\s*1=1\\s*\\)", " 1=1 ");
        if (sql.matches("([\\s\\S]*),\\s*1=1\\s*([\\s\\S]*)")
                || sql.matches("([\\s\\S]*)1=1\\s*,([\\s\\S]*)")
                || sql.matches("(?i)([\\s\\S]*)1=1\\s+or\\s+([\\s\\S]*)")
                || sql.matches("(?i)([\\s\\S]*)\\s+or\\s+1=1\\s*([\\s\\S]*)")
                || sql.matches("(?i)([\\s\\S]*)\\s+and\\s+1=1\\s*([\\s\\S]*)")
                || sql.matches("(?i)([\\s\\S]*)\\s+1=1\\s+and\\s*([\\s\\S]*)")
                || sql.matches("([\\s\\S]*)\\(+\\s*1=1\\s*\\)([\\s\\S]*)"))
            sql = replaceWhere(sql);
        return sql;
    }

    private void createInsertSql() {
        String sql = result.getSqlConfig().getSql();
        List<String> ffList = result.getSqlConfig().getFfList();
        Map<String, Object> map = result.getParams();
        int index = 0;
        for (SqlAnalysisNode node : result.getSqlConfig().getGroup()) {
            if (map.containsKey(node.getParam())) {
                sql = sql.replace(node.getWhole(), ":" + node.getParam());
            } else {
                sql = sql.replace(node.getWhole(), "");
//                sql = sql.replace("`" + node.getParam() + '`', "");
                sql = sql.replace(ffList.get(index), "");

            }
            index = index + 1;
        }
        sql = sql.replaceAll(",(\\s*,)+", ",");
//        sql = sql.replaceAll("\\s\\s", "");
        sql = sql.replaceAll("\\(\\s*,", "(");
        sql = sql.replaceAll(",\\s*\\)", ")");
        result.setSql(sql);
    }

    private void dealParamIntercepts() {
        List<Pair<String, IFunIntercept>> list = result.getSqlConfig().getParamIntercepts();
        logger.info("有{}个paramIntercept需要处理", list.size());
        if (!list.isEmpty()) {
            for (Pair<String, IFunIntercept> intercept : list) {
                intercept.getValue().intercept(intercept.getKey(), result.getParams(), result);
            }
        }
    }

    public void dealFunIntercept(Object dataSet) {
        List<Pair<String, IFunIntercept>> list = result.getSqlConfig().getFunIntercepts();
        logger.info("有{}个funIntercept需要处理", list.size());
        logger.info("funIntercepts list :" + JsonUtils.toJson(list));
        if (!list.isEmpty() && dataSet instanceof List) {
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) dataSet;
            for (Map<String, Object> data : dataList) {
                for (Pair<String, IFunIntercept> intercept : list) {
                    if (intercept != null) {
                        logger.info(intercept.getValue().getClass().getName());
                        intercept.getValue().intercept(intercept.getKey(), data, result);
                    }
                }
            }
        }
    }

    public void dealSqlIntercepts() {
        Map<String, Object> map = result.getParams();
        String key = "withRelation";
        if (!map.containsKey(key) || Boolean.valueOf(map.get("withRelation").toString())) {
            List<SqlConfig> list = result.getSqlConfig().getSqlIntercepts();
            logger.info("有{}个sqlIntercept需要处理", list.size());
            if (!list.isEmpty()) {
                for (SqlConfig sqlConfig : list) {
                    if (sqlConfig.getId().equals(result.getSqlConfig().getId())) continue;
                    this.dealSqlIntercept(sqlConfig);
                }
            }
        }
    }

    private void dealSqlIntercept(SqlConfig sqlConfig) {
        SqlConfig realSqlConfig;
        if (!Strings.isNullOrEmpty(sqlConfig.getRefId()))
            realSqlConfig = SqlConfigManager.getSqlConfig(sqlConfig.getRefId());
        else realSqlConfig = sqlConfig;
        Map<String, Object> map = cloneMap(oParams);
        List<String> rs = Splitter.on("|").splitToList(sqlConfig.getRelationKey());
        if (!Strings.isNullOrEmpty(sqlConfig.getRelationKey())) {
            String relationKey = rs.get(0);
            if (relationKey.equals(SqlConfig.RESULTASINT))
                map.put(relationKey, Integer.valueOf(result.getResult().toString()));
            else {
                List<Object> _list = fetchAsArray((List<Map<String, Object>>) result.getResult(), relationKey);
                if (_list.size() == 0) return;
                map.put(relationKey, _list);
            }

        }
        SqlResult _ret = SqlExecutor.exec(realSqlConfig, map);
        result.setChilds(_ret);
        Object ret = _ret.getResult();

        if (Strings.isNullOrEmpty(sqlConfig.getExtend())) return;
        if (ret instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) ret;
            if (list.isEmpty()) return;
            String type = sqlConfig.getExtend();
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.getResult();
            String mappby = TableStruct.getRealMappBy(list.get(0), rs.get(1));
            if (type.equals(ExtendUtils.EXTEND))
                data = ExtendUtils.extend(data, list, rs.get(0), mappby);
            else if (type.equals(ExtendUtils.ONE2ONE))
                data = ExtendUtils.extendOne2One(data, list, rs.get(0), mappby, sqlConfig.getFillKey());
            else if (type.equals(ExtendUtils.ONE2MANY))
                data = ExtendUtils.extendOne2Many(data, list, rs.get(0), mappby, sqlConfig.getFillKey());
            result.setResult(data);
        }
    }

    public void dealReturnType() {
        logger.info("dealReturnType");
        if (!Strings.isNullOrEmpty(result.getSqlConfig().getReturnType()) && result.getSqlConfig().getSqlType().equals(SqlConfig.SELECT)) {
            Class clz = result.getSqlConfig().getKlass();
            List datas = (List<Map>) result.getResult();
            if (datas != null && datas.size() > 0) {
                for (int i = 0; i < datas.size(); i++) {
                    datas.set(i, JsonUtils.convertWriteNull(datas.get(i), clz));
                }
            }
            result.setResult(datas);
        }
    }

    private static Map<String, Object> cloneMap(Map<String, Object> map) {
        Map<String, Object> params = Maps.newHashMap();
        if (map != null) params.putAll(map);
        return params;
    }

    public static List<Object> fetchAsArray(List<Map<String, Object>> list, String key) {
        List<Object> params = Lists.newArrayList();
        Map<Object, Boolean> hash = Maps.newHashMap();
        for (Map<String, Object> map : list) {
            Object val = Wrap.getWrap(map).getValue(key);
            if (!hash.containsKey(val)) {
                params.add(val);
                hash.put(val, true);
            }
        }

        return params;
    }

    public int insert(String dbName, String tableName, String primaryKeyName, Map data) {
        return batchInsert(dbName, tableName, primaryKeyName, Lists.newArrayList(data));
    }

    public int batchInsert(String dbName, String tableName, String primaryKeyName, List<Map> dataList) {
        Map data = dataList.get(0);
        List<String> columns = new ArrayList<String>(data.keySet());
        String sql = "insert into `" + tableName + "` (`" + Joiner.on("`,`").join(columns) + "`) values (:" + Joiner.on(",:").join(columns) + ")";
        NamedParameterJdbcOperations jdbc = SqlSession.instance.getJdbcTemplate(dbName);
        dataList.forEach(map -> {
            columns.forEach(key -> {
                if (!map.containsKey(key)) map.put(key, null);
            });
        });
        int[] ints = jdbc.batchUpdate(sql, dataList.toArray(new Map[dataList.size() - 1]));
        return ints.length;
    }

    public int batchSave(String dbName, String tableName, String primaryKeyName, List<Map> dataList) {
        throw new RuntimeException("暂不支持");
    }

    public <T extends ModelBase> long insert(T model) {
        return model.insert2();
    }

    public <T extends ModelBase> long update(T model) {
        return model.update2();
    }

    public <T extends ModelBase> long save(T model) {
        TableStruct table = TableStruct.getTableStruct(model.getClass().getName());
        BeanMap thisMap = BeanMap.create(model);
        if (thisMap.get(table.getPrimaryField()) != null) {
            try {
                ModelBase clone = model.getClass().newInstance();
                BeanMap beanMap = BeanMap.create(clone);
                beanMap.put(table.getPrimaryField(), thisMap.get(table.getPrimaryField()));
                clone = clone.selectOne();
                if (clone != null) {
                    Models.recordModifyLog(clone, model);
                    return update(model);
                } else {
                    Models.recordModifyLog(model.getClass().newInstance(), model);
                    return insert(model);
                }
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e.getCause());
            }
        } else {
            return insert(model);
        }
    }

}
