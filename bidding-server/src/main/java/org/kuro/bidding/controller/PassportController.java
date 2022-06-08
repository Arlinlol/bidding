package org.kuro.bidding.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.github.xiaoymin.knife4j.annotations.ApiSupport;
import com.google.code.kaptcha.Producer;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.kuro.bidding.enums.UserStatus;
import org.kuro.bidding.exceptions.BusinessException;
import org.kuro.bidding.model.bo.LoginInfoBo;
import org.kuro.bidding.model.entity.User;
import org.kuro.bidding.model.result.Result;
import org.kuro.bidding.model.result.ResultCode;
import org.kuro.bidding.service.UserService;
import org.kuro.bidding.utils.RedisKeyUtil;
import org.kuro.bidding.utils.RedisOperator;
import org.kuro.bidding.utils.SMSUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@ApiSupport(order = 1)
@RestController
@RequestMapping("api/v1")
@Api(value = "登陆相关API", tags = {"登陆"})
public class PassportController {

    @Autowired
    private SMSUtils smsUtils;

    @Autowired
    private UserService userService;

    @Autowired
    private Producer defaultKaptcha;

    @Autowired
    private RedisOperator redis;

    @ApiOperation(value = "登陆验证码", notes = "获取登陆图片验证码", httpMethod = "GET")
    @GetMapping("/pub/passport/code")
    public Result fetchCodeApi() {
        String code;
        String base64 = "data:image/jpg;base64,";
        ByteArrayOutputStream jpegOutputStream = new ByteArrayOutputStream();
        try {
            code = defaultKaptcha.createText();
            //使用生产的验证码字符串返回一个BufferedImage对象并转为byte写入到byte数组中
            BufferedImage challenge = defaultKaptcha.createImage(code);
            ImageIO.write(challenge, "jpg", jpegOutputStream);
            base64 += Base64.encode(jpegOutputStream.toByteArray());
            jpegOutputStream.flush();
            jpegOutputStream.close();
        } catch (IllegalArgumentException | IOException e) {
            throw new BusinessException(ResultCode.CODE_CREATE_ERROR);
        }

        // 将验证码存入 Redis
        String key = IdUtil.fastSimpleUUID();
        String kaptchaKey = RedisKeyUtil.getKaptchaKey(key);
        Map<String, String> res = new HashMap<>();
        res.put("key", key);
        res.put("code", base64);
        redis.set(kaptchaKey, code, 60);

        return Result.ok().data(res);
    }


    @ApiOperation(value = "账号登录", notes = "账号登陆", httpMethod = "POST")
    @ApiResponses({
            @ApiResponse(code = 200, message = "登录成功!"),
            @ApiResponse(code = 505, message = "验证码过期或不匹配，请稍后重试!"),
            @ApiResponse(code = 507, message = "账号被冻结，请联系管理员!"),
            @ApiResponse(code = 999, message = "请求失败!")
    })
    @PostMapping("/pub/passport/login")
    public Result loginApi(
            @RequestBody @Valid LoginInfoBo loginInfoBo,
            HttpServletRequest request
    ) {
        // 检查验证码
        String code = loginInfoBo.getCode();
        String key = loginInfoBo.getKey();
        String redisKey = RedisKeyUtil.getKaptchaKey(key);
        String imageCode = redis.get(redisKey);
        if (StrUtil.isBlank(imageCode) || !imageCode.equalsIgnoreCase(code)) {
            return Result.error(ResultCode.CODE_ERROR);
        }

        // 判断用户是否存在
        String mobile = loginInfoBo.getMobile();
        User user = userService.queryUserByMobile(mobile);
        if (user == null || user.getStatus().equals(UserStatus.DELETE.type)) {
            return Result.error(ResultCode.ACCOUNT_NOT_EXIST);
        }

        // 用户是否被冻结
        if (Objects.equals(user.getStatus(), UserStatus.FREEZE.type)) {
            return Result.error(ResultCode.ACCOUNT_DISABLE);
        }

        // 检查密码是否正确
        String password = loginInfoBo.getPassword();
        boolean isMatch = BCrypt.checkpw(password, user.getPassword());
        if (!isMatch) {
            return Result.error(ResultCode.ACCOUNT_INFO_ERROR);
        }

        // 登陆处理
        StpUtil.login(user.getId());
        SaSession session = StpUtil.getSession();
        session.set(RedisKeyUtil.getSessionUserKey(), user);
        SaTokenInfo info = StpUtil.getTokenInfo();

        // todo 记录登陆日志

        // 返回用户信息和token
        Map<String, Object> map = new HashMap<>();
        map.put("token", info.getTokenValue());
        map.put("user", user);
        return Result.ok(ResultCode.LOGIN_SUCCESS).data(map);
    }


    @ApiOperation(value = "退出登陆", notes = "退出登陆", httpMethod = "POST")
    @PostMapping("/pri/passport/logout")
    public Result logoutApi() {
        StpUtil.logout();
        return Result.ok(ResultCode.LOGOUT_SUCCESS);
    }
}
