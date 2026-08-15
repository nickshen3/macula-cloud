import config from "@/config"
import http from "@/utils/request"

export default {
    systemToken: {
        // P2-3: BFF 代理——client 凭证由 IAM 服务端注入，前端产物不再包含 secret
        url: `${config.IAM_URL}/login/token`,
        name: "macula V5 system提供隐式获取登录token接口",
        post: async function (data = {}, config = {}) {
            return await http.post(this.url, data, {
                headers: {
                    'Content-Type': 'application/json'
                }
            })
        }
    },
    getUserInfo: {
        url: `${config.API_URL}/${config.MODEL.system}/api/v1/users/me`,
        name: "macula V5 system提供获取当前登录用户信息接口",
        get: async function () {
            return await http.get(this.url)
        }
    },
    getRoutes: {
        url: `${config.API_URL}/${config.MODEL.system}/api/v1/menus/routes`,
        name: "macula V5 system提供获取当前菜单接口",
        get: async function () {
            return await http.get(this.url)
        }
    }
}
