/*
Copyright (c) REBUILD <https://getrebuild.com/> and/or its owners. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.core.service.trigger;

import cn.devezhao.persist4j.Entity;
import cn.devezhao.persist4j.engine.ID;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rebuild.core.Application;
import com.rebuild.core.configuration.ConfigBean;
import com.rebuild.core.configuration.ConfigManager;
import com.rebuild.core.metadata.MetadataHelper;
import com.rebuild.core.service.query.AdvFilterParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.commons.lang.StringUtils;

import java.text.MessageFormat;
import java.util.*;

/**
 * 触发器管理
 *
 * @author devezhao zhaofang123@gmail.com
 * @since 2019/05/27
 */
@Slf4j
public class RobotTriggerManager implements ConfigManager {

    public static final RobotTriggerManager instance = new RobotTriggerManager();

    private RobotTriggerManager() {
    }

    /**
     * @param record
     * @param when
     * @return
     */
    public TriggerAction[] getActions(ID record, TriggerWhen... when) {
        return filterActions(MetadataHelper.getEntity(record.getEntityCode()), record, when);
    }

    /**
     * @param entity
     * @param when
     * @return
     */
    public TriggerAction[] getActions(Entity entity, TriggerWhen... when) {
        return filterActions(entity, null, when);
    }

    private static final ThreadLocal<List<String>> TRIGGERS_CHAIN_4DEBUG = ThreadLocal.withInitial(ArrayList::new);
    /**
     * @param record
     * @param entity
     * @param when
     * @return
     */
    private TriggerAction[] filterActions(Entity entity, ID record, TriggerWhen... when) {
        List<TriggerAction> actions = new ArrayList<>();
        for (ConfigBean cb : getConfig(entity)) {
            if (allowedWhen(cb, when)) {
                if (record == null || !isFiltered((JSONObject) cb.getJSON("whenFilter"), record)) {
                    ActionContext ctx = new ActionContext(record, entity, cb.getJSON("actionContent"), cb.getID("id"));
                    TriggerAction o = ActionFactory.createAction(cb.getString("actionType"), ctx);
                    actions.add(o);

                    if (Application.devMode() || log.isDebugEnabled()) {
                        TRIGGERS_CHAIN_4DEBUG.get().add(o.getType() + "#" + ctx.getConfigId()
                                + " on " + when[0] + " " + entity.getName() + " with " + record);
                    }
                }
            }
        }

        if (!TRIGGERS_CHAIN_4DEBUG.get().isEmpty()) {
            log.info("Record ({}) triggers chain : \n  > {}",
                    record, StringUtils.join(TRIGGERS_CHAIN_4DEBUG.get(), "\n  > "));
        }

        return actions.toArray(new TriggerAction[0]);
    }

    /**
     * 允许的动作
     *
     * @param entry
     * @param when
     * @return
     */
    private boolean allowedWhen(ConfigBean entry, TriggerWhen... when) {
        if (when.length == 0) {
            return true;
        }
        int whenMask = entry.getInteger("when");
        for (TriggerWhen w : when) {
            if ((whenMask & w.getMaskValue()) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否过滤
     *
     * @param whenFilter
     * @param record
     * @return
     */
    private boolean isFiltered(JSONObject whenFilter, ID record) {
        if (whenFilter == null || whenFilter.isEmpty()) {
            return false;
        }

        Entity entity = MetadataHelper.getEntity(record.getEntityCode());
        AdvFilterParser filterParser = new AdvFilterParser(whenFilter);
        String sqlWhere = StringUtils.defaultIfBlank(filterParser.toSqlWhere(), "1=1");
        String sql = MessageFormat.format(
                "select {0} from {1} where {0} = ? and {2}",
                entity.getPrimaryField().getName(), entity.getName(), sqlWhere);
        Object matchs = Application.createQueryNoFilter(sql).setParameter(1, record).unique();
        return matchs == null;
    }

    /**
     * @param entity
     * @return
     */
    @SuppressWarnings("unchecked")
    protected List<ConfigBean> getConfig(Entity entity) {
        final String cKey = "RobotTriggerManager-" + entity.getName();
        Object cached = Application.getCommonsCache().getx(cKey);
        if (cached != null) {
            return (List<ConfigBean>) cached;
        }

        Object[][] array = Application.createQueryNoFilter(
                "select when,whenFilter,actionType,actionContent,configId from RobotTriggerConfig" +
                        " where belongEntity = ? and when > 0 and isDisabled = 'F' order by priority desc")
                .setParameter(1, entity.getName())
                .array();

        ArrayList<ConfigBean> entries = new ArrayList<>();
        for (Object[] o : array) {
            ConfigBean entry = new ConfigBean()
                    .set("when", o[0])
                    .set("whenFilter", JSON.parseObject((String) o[1]))
                    .set("actionType", o[2])
                    .set("actionContent", JSON.parseObject((String) o[3]))
                    .set("id", o[4]);
            entries.add(entry);
        }

        Application.getCommonsCache().putx(cKey, entries);
        return entries;
    }

    @Override
    public void clean(Object entity) {
        final String cKey = "RobotTriggerManager-" + ((Entity) entity).getName();
        Application.getCommonsCache().evict(cKey);
        Application.getCommonsCache().evict(CKEY_TARF);
    }

    private static final String CKEY_TARF = "TriggersAutoReadonlyFields";
    /**
     * 获取触发器中涉及的自动只读字段
     *
     * @param entity
     * @return
     */
    public Set<String> getAutoReadonlyFields(String entity) {
        @SuppressWarnings("unchecked")
        Map<String, Set<String>> fieldsMap = (Map<String, Set<String>>) Application.getCommonsCache().getx(CKEY_TARF);
        if (fieldsMap == null) {
            fieldsMap = this.initAutoReadonlyFields();
        }
        return Collections.unmodifiableSet(fieldsMap.getOrDefault(entity, Collections.emptySet()));
    }

    synchronized
    private Map<String, Set<String>> initAutoReadonlyFields() {
        Object[][] array = Application.createQueryNoFilter(
                "select actionContent from RobotTriggerConfig where (actionType = ? or actionType = ?) and isDisabled = 'F'")
                .setParameter(1, ActionType.FIELDAGGREGATION.name())
                .setParameter(2, ActionType.FIELDWRITEBACK.name())
                .array();

        CaseInsensitiveMap<String, Set<String>> fieldsMap = new CaseInsensitiveMap<>();
        for (Object[] o : array) {
            JSONObject content = JSON.parseObject((String) o[0]);
            if (content == null || !content.getBooleanValue("readonlyFields")) {
                continue;
            }

            String targetEntity = content.getString("targetEntity");
            targetEntity = targetEntity.split("\\.")[1];  // Field.Entity

            Set<String> fields = fieldsMap.computeIfAbsent(targetEntity, k -> new HashSet<>());
            for (Object item : content.getJSONArray("items")) {
                String targetField = ((JSONObject) item).getString("targetField");
                fields.add(targetField);
            }
        }

        Application.getCommonsCache().putx(CKEY_TARF, fieldsMap);
        return fieldsMap;
    }
}
