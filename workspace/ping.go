package main

import (
	"encoding/json"
	"log"
	"net/http"
)

// PingResponse 统一响应结构
// 项目规范：所有 API 接口必须返回 JSON 格式，且包含 code 和 message 字段
type PingResponse struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// pingHandler 处理 /ping 请求
func pingHandler(w http.ResponseWriter, r *http.Request) {
	// 项目规范：错误处理必须返回中文报错信息，禁止英文抛错
	if r.Method != http.MethodGet {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusMethodNotAllowed)
		_ = json.NewEncoder(w).Encode(PingResponse{
			Code:    http.StatusMethodNotAllowed,
			Message: "请求方法不被允许，本接口仅支持 GET 请求",
		})
		return
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(PingResponse{
		Code:    0,
		Message: "pong",
	})
}

func main() {
	http.HandleFunc("/ping", pingHandler)

	addr := ":8080"
	log.Printf("服务已启动，监听地址: http://localhost%s/ping\n", addr)
	if err := http.ListenAndServe(addr, nil); err != nil {
		// 项目规范：错误信息使用中文
		log.Fatalf("HTTP 服务启动失败: %v\n", err)
	}
}
