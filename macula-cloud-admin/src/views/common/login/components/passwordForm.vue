<!--
  - Copyright (c) 2023 Macula
  -   macula.dev, China
  -
  - Licensed under the Apache License, Version 2.0 (the "License");
  - you may not use this file except in compliance with the License.
  - You may obtain a copy of the License at
  -
  -    http://www.apache.org/licenses/LICENSE-2.0
  -
  - Unless required by applicable law or agreed to in writing, software
  - distributed under the License is distributed on an "AS IS" BASIS,
  - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  - See the License for the specific language governing permissions and
  - limitations under the License.
  -->

<template>
	<el-form ref="loginForm" :model="form" :rules="rules" label-width="0" size="large">
		<el-form-item prop="user">
			<el-input v-model="form.user" prefix-icon="el-icon-user" clearable :placeholder="$t('login.userPlaceholder')">
				<template #append>
					<el-select v-model="userType" style="width: 130px;">
						<el-option :label="$t('login.admin')" value="admin"></el-option>
						<el-option :label="$t('login.user')" value="user"></el-option>
					</el-select>
				</template>
			</el-input>
		</el-form-item>
		<el-form-item prop="password">
			<el-input v-model="form.password" prefix-icon="el-icon-lock" clearable show-password
				:placeholder="$t('login.PWPlaceholder')"></el-input>
		</el-form-item>
		<el-form-item style="margin-bottom: 10px;">
			<el-col :span="12">
				<el-checkbox :label="$t('login.rememberMe')" v-model="form.autologin"></el-checkbox>
			</el-col>
			<el-col :span="12" class="login-forgot">
				<router-link to="/reset_password">{{ $t('login.forgetPassword') }}？</router-link>
			</el-col>
		</el-form-item>
		<el-form-item>
			<el-button type="primary" style="width: 100%;" :loading="islogin" round @click="login">{{ $t('login.signIn')
			}}</el-button>
		</el-form-item>
		<div class="login-reg">
			{{ $t('login.noAccount') }} <router-link to="/user_register">{{ $t('login.createAccount') }}</router-link>
		</div>
	</el-form>
</template>

<script>
import { useTenantStore } from '@/stores/tenant';
import { mapActions } from 'pinia';

export default {
	data() {
		return {
			userType: 'admin',
			form: {
				user: "admin",
				password: "admin",
				autologin: false
			},
			rules: {
				user: [
					{ required: true, message: this.$t('login.userError'), trigger: 'blur' }
				],
				password: [
					{ required: true, message: this.$t('login.PWError'), trigger: 'blur' }
				]
			},
			islogin: false,
		}
	},
	watch: {
		userType(val) {
			if (val == 'admin') {
				this.form.user = 'admin'
				this.form.password = 'admin'
			} else if (val == 'user') {
				this.form.user = 'user'
				this.form.password = 'user'
			}
		}
	},
	mounted() {

	},
	methods: {
		...mapActions(useTenantStore, ['pushTenantOptions', 'updateTenantLabel', 'updateTenantId', 'clearTenantOptions']),
		async login() {
			// P3-2: 授权码+PKCE（BFF 路线）——跳转 IAM 统一认证，token 由服务端 session 承载，
			// 浏览器仅持 HttpOnly Cookie；账密在 IAM 官方登录表单提交，前端不再经手
			this.islogin = true
			window.location.href = import.meta.env.VITE_APP_IAM_URL + '/auth/authorize'
		
		}
	}
}

</script>

<style></style>
