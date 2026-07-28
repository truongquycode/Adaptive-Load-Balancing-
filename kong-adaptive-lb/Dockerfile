FROM kong:3.9-alpine

USER root

# Install missing dependencies for luarocks (like git or curl if necessary)
# Usually kong-alpine has luarocks already. We just need to install lua-resty-http.
RUN luarocks install lua-resty-http 0.17.2.1-0

USER kong
