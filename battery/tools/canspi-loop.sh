#!/system/bin/sh
# Runs on the head unit as root (pushed and started by canspi-log.sh).
# Every INTERVAL seconds it subscribes to the local nanomq "canspi" topic for about one
# second and keeps the raw MQTT bytes, up to MAX_FILES captures.
# Subscribe-only: the only packets sent are CONNECT, SUBSCRIBE and DISCONNECT. Never publish:
# rule, control and cloud-upload topics share this anonymous broker.
DIR=${DIR:-/data/local/tmp/catfish-canspi}
INTERVAL=${1:-60}
MAX_FILES=${2:-1440}
PORT=${PORT:-1883}
NC=${NC:-toybox nc}

# CONNECT: MQTT 3.1.1, clean session, keepalive 60 s, client id "catfish-canspi-log"
CONNECT='\020\036\000\004MQTT\004\002\000\074\000\022catfish-canspi-log'
# SUBSCRIBE: packet id 1, topic "canspi", QoS 0
SUBSCRIBE='\202\013\000\001\000\006canspi\000'
DISCONNECT='\340\000'

mkdir -p "$DIR"
echo $$ > "$DIR/pid"
count=0
while [ "$count" -lt "$MAX_FILES" ]; do
    # 40000 bytes keeps the acks plus two ~15.5 KB snapshots
    (printf "$CONNECT$SUBSCRIBE"; sleep 1; printf "$DISCONNECT") \
        | timeout 3 $NC 127.0.0.1 "$PORT" | head -c 40000 > "$DIR/$(date +%s).bin"
    count=$((count + 1))
    sleep "$INTERVAL"
done
rm -f "$DIR/pid"
