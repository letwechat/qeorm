package qeorm;

import com.google.common.base.Splitter;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import qeorm.utils.ExtendUtils;
import qeorm.utils.JsonUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnhanceModel <T extends ModelBase> implements MethodInterceptor {
    private static Map<String, Field> fieldMap = new HashMap<>();
    //要代理的原始对象
    private T target;
    private boolean deep;

    public T createProxy(T target, boolean deep) {
        this.target = target;
        this.deep = deep;
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(this.target.getClass());
        enhancer.setCallback(this);
        enhancer.setClassLoader(target.getClass().getClassLoader());
        return (T) enhancer.create();
    }

    @Override
    public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
        if (target == null) return null;
        Object result = methodProxy.invoke(target, objects);
        if (method.getName().startsWith("set")) {
            String filedName = method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4);
            Object val = objects[0];
            if (val == null) target.addKeyNullSet(filedName);
            else target.removeKeyNullSet(filedName);
        }
        if (result == null && method.getName().startsWith("get")) {
            String filedName = method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4);
            String id = target.sqlIndexId(SqlConfig.SELECT) + "." + filedName;
            SqlConfig sqlConfig = target.createSqlConfig(SqlConfig.SELECT).getSqlIntercepts()
                    .stream().filter(config -> config.getId().equals(id)).findAny().get();
            if (sqlConfig != null) {
                String key = Splitter.on("|").splitToList(sqlConfig.getRelationKey()).get(0);
                String byFiled = TableStruct.getRealMappBy(target, key);
                Map data = JsonUtils.convert(target, Map.class);
                if (data.containsKey(byFiled)) {
                    Map<String, Object> params = new HashMap<>();
                    params.put(key, data.get(byFiled));
                    SqlResult sqlResult = SqlExecutor.exec(sqlConfig, params);
                    if (sqlResult.isOk()) {
                        List list = (List) sqlResult.getResult();
                        if (list.size() > 0) {
                            List _list = new ArrayList();
                            for (int i = 0; i < list.size(); i++) {
                                _list.add(enhance(list.get(i)));
                            }
                            if (sqlConfig.getExtend().equals(ExtendUtils.ONE2ONE)) {
                                result = _list.get(0);
                            } else {
                                result = _list;
                            }
                            getTargetField(filedName).set(target, result);
                        }

                    }
                }
            }
        }


        return result;
    }

    private Object enhance(Object obj) {
        if (deep && obj instanceof ModelBase)
            return ((ModelBase) obj).enhance();
        return obj;
    }

    private Field getTargetField(String filedName) throws NoSuchFieldException {
        String key = target.getClass() + "." + filedName;
        if (!fieldMap.containsKey(key)) {
            Field field = target.getClass().getDeclaredField(filedName);
            field.setAccessible(true);
            fieldMap.put(key, field);
        }
        return fieldMap.get(key);
    }

    public static <E extends ModelBase> E create(E target, boolean deep ){
        return (E) new EnhanceModel().createProxy(target, deep);
    }
    public static <E extends ModelBase> E create(E target ){
        return (E) new EnhanceModel().createProxy(target, false);
    }
}
