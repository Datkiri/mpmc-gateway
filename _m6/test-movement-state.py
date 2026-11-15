"""Execute the gateway's movement methods in an isolated Java regression harness."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "gateway-build/ViaBedrock/src/main/java/net/raphimc/viabedrock"


def block(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


math = (SOURCE / "api/util/MathUtil.java").read_text()
player = (SOURCE / "api/model/entity/ClientPlayerEntity.java").read_text()
packets = (SOURCE / "protocol/packet/ClientPlayerPackets.java").read_text()
methods = [block(math, "public static Position2f calculateMovementDirections(")]
markers = [
    "public void updatePlayerPosition(final short flags)",
    "public void updatePlayerPosition(final double x, final double y, final double z, final short flags)",
    "public void updatePlayerPosition(final double x, final double y, final double z, final float yaw",
    "public void updatePlayerPosition(final float yaw",
    "private boolean preMove(",
]
methods += [block(player, marker) for marker in markers]
sprint_start = packets.index("            if (clientPlayer.inputFlags().contains(InputFlag.SPRINT))")
sprint_end = packets.index("            if (clientPlayer.inputFlags().contains(InputFlag.JUMP) &&", sprint_start)
sprint = packets[sprint_start:sprint_end]
transitions = "\n".join(block(packets, "case " + action + " ->") for action in ("START_SPRINTING", "STOP_SPRINTING"))

harness = r'''
import java.util.*;
class MovementStateRegression {
    enum PlayerAuthInputData { Left, Right, Up, Down, SprintDown, Sprinting, StartSprinting, StopSprinting, StartFlying, PerformBlockActions }
    enum PlayerCommandAction { START_SPRINTING, STOP_SPRINTING }
    enum InputFlag { SPRINT }
    enum MovePlayerFlag {
        ON_GROUND(1), HORIZONTAL_COLLISION(2);
        final int bit; MovePlayerFlag(int bit) { this.bit=bit; } int getBit() { return bit; }
    }
    enum Relative { NONE, ROTATION }
    record Position2f(float x, float y) {}
    record Position3f(float x, float y, float z) {}
    static class ChunkTracker {
        boolean unloaded;
        boolean isInUnloadedChunkSection(Position3f position) { return unloaded; }
    }
    static class User {
        final ChunkTracker tracker = new ChunkTracker();
        <T> T get(Class<T> type) { return type.cast(tracker); }
    }
    final User user = new User();
    Position3f position = new Position3f(0, 1.62F, 0), rotation = new Position3f(0,0,0);
    boolean onGround=true, horizontalCollision, serverSideTeleportConfirmed, waitingForPositionSync;
    boolean initiallySpawned=true, wasInsideUnloadedChunk, sprinting;
    Object dimensionChangeInfo;
    int teleports;
    Set<InputFlag> inputs=EnumSet.noneOf(InputFlag.class);
    Set<PlayerAuthInputData> output=EnumSet.noneOf(PlayerAuthInputData.class);
    float eyeOffset() { return 1.62F; }
    void sendPlayerPositionPacketToClient(Relative relatives) { teleports++; }
    Set<InputFlag> inputFlags() { return inputs; }
    boolean isSprinting() { return sprinting; }
    void setSprinting(boolean value) { sprinting=value; }
    Set<PlayerAuthInputData> authInputData() { return output; }
    void sprintCommand(PlayerCommandAction action) {
        MovementStateRegression clientPlayer=this;
        switch(action) { TRANSITION_BODY }
    }
    void addAuthInputData(PlayerAuthInputData... flags) { output.addAll(Arrays.asList(flags)); }
    void sprintFlags() {
        MovementStateRegression clientPlayer=this;
        SPRINT_BODY
    }
    SOURCE_METHODS
    static int checks, failures;
    static void check(boolean condition, String name) {
        checks++;
        if (!condition) { failures++; System.err.println("FAIL " + name); }
    }
    static void move(MovementStateRegression p, int mode, short flags) {
        switch(mode) {
            case 0 -> p.updatePlayerPosition(flags);
            case 1 -> p.updatePlayerPosition(0D,0D,0D,flags);
            case 2 -> p.updatePlayerPosition(0D,0D,0D,0F,0F,flags);
            case 3 -> p.updatePlayerPosition(0F,0F,flags);
        }
    }
    public static void main(String[] args) {
        for (int mask=0;mask<16;mask++) {
            Set<PlayerAuthInputData> flags=EnumSet.noneOf(PlayerAuthInputData.class);
            if ((mask&1)!=0) flags.add(PlayerAuthInputData.Left);
            if ((mask&2)!=0) flags.add(PlayerAuthInputData.Right);
            if ((mask&4)!=0) flags.add(PlayerAuthInputData.Up);
            if ((mask&8)!=0) flags.add(PlayerAuthInputData.Down);
            for (boolean sneak : new boolean[]{false,true}) {
                double x=((mask&1)!=0?1:0)-((mask&2)!=0?1:0);
                double y=((mask&4)!=0?1:0)-((mask&8)!=0?1:0);
                double length=Math.sqrt(x*x+y*y);
                if(length>1) { x/=length; y/=length; }
                if(sneak) { x*=.3; y*=.3; }
                Position2f actual=calculateMovementDirections(flags,sneak);
                check(Math.abs(actual.x()-x)<1e-6 && Math.abs(actual.y()-y)<1e-6,
                    "direction mask="+mask+" sneak="+sneak);
            }
        }
        for(int mode=0;mode<4;mode++) {
            MovementStateRegression p=new MovementStateRegression();
            move(p,mode,(short)3);
            check(p.horizontalCollision,"collision enters overload="+mode);
            move(p,mode,(short)1);
            check(!p.horizontalCollision,"collision exits overload="+mode);
            check(p.position.equals(new Position3f(0,1.62F,0)) && p.onGround,
                "collision preserves position/ground overload="+mode);
            for(int gate=0;gate<4;gate++) {
                p=new MovementStateRegression();
                if(gate==0) p.waitingForPositionSync=true;
                if(gate==1) p.initiallySpawned=false;
                if(gate==2) p.dimensionChangeInfo=new Object();
                if(gate==3) p.user.tracker.unloaded=true;
                move(p,mode,(short)3);
                check(!p.horizontalCollision,"collision respects sync gate="+gate+" overload="+mode);
            }
        }
        for(boolean key:new boolean[]{false,true}) {
            for(boolean active:new boolean[]{false,true}) {
                MovementStateRegression p=new MovementStateRegression();
                if(key) p.inputs.add(InputFlag.SPRINT);
                p.sprinting=active;
                p.sprintFlags();
                check(p.output.contains(PlayerAuthInputData.SprintDown)==key,
                    "sprint key="+key+" active="+active);
                check(p.output.contains(PlayerAuthInputData.Sprinting)==active,
                    "sprint state key="+key+" active="+active);
            }
        }
        for(int length=1;length<=4;length++) {
            for(int sequence=0;sequence<(1<<length);sequence++) {
                MovementStateRegression p=new MovementStateRegression();
                p.output.addAll(EnumSet.of(PlayerAuthInputData.StartFlying, PlayerAuthInputData.PerformBlockActions));
                for(int step=0;step<length;step++) {
                    boolean active=(sequence&(1<<step))!=0;
                    p.sprintCommand(active?PlayerCommandAction.START_SPRINTING:PlayerCommandAction.STOP_SPRINTING);
                    check(p.sprinting==active && p.output.contains(PlayerAuthInputData.StartSprinting)==active
                            && p.output.contains(PlayerAuthInputData.StopSprinting)!=active,
                        "sprint sequence="+sequence+" length="+length+" step="+step);
                    check(p.output.containsAll(EnumSet.of(PlayerAuthInputData.StartFlying, PlayerAuthInputData.PerformBlockActions)),
                        "sprint preserves unrelated flags");
                }
            }
        }
        System.out.println("Movement regression: "+(checks-failures)+"/"+checks+" checks passed");
        if(failures!=0) System.exit(1);
    }
}
'''.replace("SPRINT_BODY", sprint).replace("TRANSITION_BODY", transitions).replace("SOURCE_METHODS", "\n".join(methods))

# Keep all test artifacts on D; execute actual method bodies without a gateway build.
with tempfile.TemporaryDirectory(prefix="movement-state-", dir=ROOT / "tmp") as directory:
    test_file = Path(directory) / "MovementStateRegression.java"
    test_file.write_text(harness)
    result = subprocess.run([
        "java", "-XX:-UsePerfData", f"-Djava.io.tmpdir={directory}", str(test_file)
    ], check=False)
    raise SystemExit(result.returncode)
