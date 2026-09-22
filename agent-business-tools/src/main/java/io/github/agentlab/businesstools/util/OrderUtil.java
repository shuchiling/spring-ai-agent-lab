package io.github.agentlab.businesstools.util;

import org.apache.commons.lang3.StringUtils;

public class OrderUtil {

    /**
     * 校验订单号是否合法
     *
     */
    public static boolean isValidOrderNo(String orderNo) {
        if (StringUtils.isBlank(orderNo)) {
            return false;
        }
        String regex = "^ORD-\\d{8}-\\d{4}$";
        return orderNo.matches(regex);
    }
}
