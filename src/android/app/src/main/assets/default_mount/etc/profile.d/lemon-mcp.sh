# 小柠檬内核 MCP 自启（幂等探测，不阻塞登录）
# 8798 无监听且资产存在时后台拉起 HTTP MCP daemon
if [ -f /usr/local/share/lemon/lemon_http_mcp.py ]; then
  if ! curl -s -m 1 http://127.0.0.1:8798/ >/dev/null 2>&1; then
    (nohup python3 /usr/local/share/lemon/lemon_http_mcp.py >> /tmp/lemon_http_mcp.log 2>&1 &)
  fi
fi
