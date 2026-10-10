# Skald build-worker egress gateway: squid with the measured deny-by-default rule
# (WORKPLAN-BUNDLES.md "Build sandbox and dependency network"; T5 gate F6 part 4). GENERATED
# by dev/worker-artifacts.py --write from dev/egress_gateway.py; checked by the same script.
# The configuration is not baked in: the launcher writes squid.conf per attempt
# (publisher.worker.GatewayConfig, held to egress_gateway.squid_conf).
FROM alpine@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b
RUN apk add --no-cache squid
USER squid
CMD ["squid", "-N", "-f", "/etc/squid/squid.conf"]
