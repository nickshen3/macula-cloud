import config from "@/config"
import http from "@/utils/request"

export default {
    systemToken: {
        url: `${config.IAM_URL}/oauth2/token`,
        name: "macula V5 system提供隐式获取登录token接口",
        post: async function (data = {}, config = {}) {
            const clientId = config.params?.client_id || 'e4da4a32-592b-46f0-ae1d-784310e88423'
            const clientSecret = config.params?.client_secret || 'secret'
            const formData = new URLSearchParams()
            Object.keys(config.params || {}).forEach(k => formData.append(k, config.params[k]))
            return await http.post(this.url, formData, {
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'Authorization': 'Basic ' + btoa(clientId + ':' + clientSecret)
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
