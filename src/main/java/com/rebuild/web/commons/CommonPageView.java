/*
Copyright (c) REBUILD <https://getrebuild.com/> and/or its owners. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.web.commons;

import cn.devezhao.commons.web.ServletUtils;
import com.rebuild.core.support.License;
import com.rebuild.core.support.RebuildConfiguration;
import com.rebuild.utils.AppUtils;
import com.rebuild.utils.CommonsUtils;
import com.rebuild.web.BaseController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @author zhaofang123@gmail.com
 * @see com.rebuild.web.RebuildWebConfigurer
 * @since 09/20/2018
 */
@Controller
public class CommonPageView extends BaseController {

    @GetMapping("/")
    public void index(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (AppUtils.isMobile(request) && License.isRbvAttached()) {
            response.sendRedirect(RebuildConfiguration.getMobileUrl("/"));
        } else {
            response.sendRedirect("user/login");
        }
    }

    @GetMapping("/*.txt")
    public void txtSuffix(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String url = request.getRequestURI();
        url = url.substring(url.lastIndexOf("/") + 1);

        String content = CommonsUtils.getStringOfRes("web/" + url);

        if (content == null) {
            response.sendError(HttpStatus.NOT_FOUND.value());
        } else {
            ServletUtils.setContentType(response, ServletUtils.CT_PLAIN);
            ServletUtils.write(response, content);
        }
    }

    @GetMapping("/p/**")
    public ModelAndView page(HttpServletRequest request) {
        String p = request.getRequestURI();
        p = p.split("/p/")[1];
        return createModelAndView("/" + p);
    }
}