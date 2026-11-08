package ru.betteranimation;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Numeric cube-based Animated Java/Blockbench projects. Never executes project commands. */
public final class Model {
    public record Frame(double time, Vector3f before, Vector3f after, String interpolation) {}
    public record Animation(String name, int ticks, boolean loop, Map<String,Map<String,List<Frame>>> tracks) {}
    public record Bone(String uuid, String name, int parent, Vector3f pivot, Vector3f rotation, String item) {}
    public final String id;
    public final List<Bone> bones = new ArrayList<>();
    public final Map<String,Animation> animations = new TreeMap<>();
    public final List<String> warnings = new ArrayList<>();
    private final Map<String,JsonObject> groups = new HashMap<>(), cubes = new HashMap<>();
    private final Map<String,String> textures = new HashMap<>();
    private final Path pack;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private Model(String id, Path pack) { this.id=id; this.pack=pack; }
    public static String safe(String text) {
        if(!text.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("ID модели/анимации: латинские строчные буквы, цифры, _ и - (до 48).");
        return text;
    }
    private static String str(JsonObject o,String key,String fallback){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():fallback;}
    private static boolean enabled(JsonObject o,String key){return !o.has(key)||o.get(key).getAsBoolean();}
    private static JsonArray array(JsonObject o,String key){return o.has(key)?o.getAsJsonArray(key):new JsonArray();}
    private static float number(JsonElement v) {
        if(v==null||v.isJsonNull()) return 0;
        try {float n=Float.parseFloat(v.getAsString());if(!Float.isFinite(n))throw new NumberFormatException();return n;}
        catch(Exception e){throw new IllegalArgumentException("Выражения Molang пока не поддерживаются: "+v);}
    }
    private static Vector3f vec(JsonElement e) {
        if(e==null||e.isJsonNull())return new Vector3f();
        if(e.isJsonArray()){var a=e.getAsJsonArray();return new Vector3f(number(a.get(0)),number(a.get(1)),number(a.get(2)));}
        var o=e.getAsJsonObject();return new Vector3f(number(o.get("x")),number(o.get("y")),number(o.get("z")));
    }
    private static JsonArray jsonVec(Vector3f v){JsonArray a=new JsonArray();a.add(v.x);a.add(v.y);a.add(v.z);return a;}
    private void write(String relative,JsonElement object)throws IOException{
        Path p=pack.resolve(relative);Files.createDirectories(p.getParent());Files.writeString(p,JSON.toJson(object));
    }
    public static Model load(Path file, Path pack) throws IOException {
        String name=file.getFileName().toString(); String id=safe(name.substring(0,name.lastIndexOf('.')));
        if(Files.size(file)>32L*1024*1024)throw new IOException("Blueprint exceeds 32 MiB");
        JsonObject root;try(var reader=Files.newBufferedReader(file)){root=JsonParser.parseReader(reader).getAsJsonObject();}
        var model=new Model(id,pack);
        JsonObject resolution=root.getAsJsonObject("resolution");
        float uvX=resolution==null?1:16f/number(resolution.get("width"));
        float uvY=resolution==null?1:16f/number(resolution.get("height"));
        int textureIndex = 0;
        for(JsonElement e:array(root,"textures")){
            JsonObject t=e.getAsJsonObject();String source=str(t,"source","");
            if(!source.startsWith("data:image/png;base64,"))throw new IOException("Текстура не встроена в Blueprint: "+str(t,"name","?"));
            String key="t"+textureIndex;
            Path target=pack.resolve("assets/betteranimation/textures/item/actors/"+id+"/"+key+".png");Files.createDirectories(target.getParent());
            byte[] png=Base64.getDecoder().decode(source.substring(source.indexOf(',')+1));
            if(png.length>16*1024*1024)throw new IOException("Texture exceeds 16 MiB");
            Files.write(target,png);
            model.textures.put(str(t,"uuid",key),key);
            model.textures.put(Integer.toString(textureIndex++),key);
        }
        for(JsonElement e:array(root,"groups")){var g=e.getAsJsonObject();model.groups.put(g.get("uuid").getAsString(),g);}
        for(JsonElement e:array(root,"elements")){var c=e.getAsJsonObject();
            if(!str(c,"type","cube").equals("cube"))throw new IOException("Поддерживаются только cube-элементы; найден "+str(c,"type","?"));
            if(enabled(c,"export"))model.cubes.put(c.get("uuid").getAsString(),c);
        }
        JsonArray roots = new JsonArray();
        JsonArray rootCubes = new JsonArray();
        for (JsonElement node : array(root, "outliner")) {
            if (node.isJsonPrimitive()) rootCubes.add(node); else roots.add(node);
        }
        if (!rootCubes.isEmpty() || roots.isEmpty()) {
            if (roots.isEmpty() && rootCubes.isEmpty()) model.cubes.keySet().stream().sorted().forEach(rootCubes::add);
            JsonObject group = new JsonObject();
            group.addProperty("uuid", "__root");
            group.addProperty("name", "root");
            group.add("children", rootCubes);
            roots.add(group);
        }
        for (JsonElement node : roots) model.bone(node, -1, uvX, uvY);
        if (model.bones.stream().noneMatch(b -> b.item() != null)) throw new IOException("No visible cubes");
        for(JsonElement e:array(root,"animations")){
            JsonObject a=e.getAsJsonObject();String animName=safe(str(a,"name","animation").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_"));
            Map<String,Map<String,List<Frame>>> tracks=new HashMap<>();
            JsonObject animators=a.getAsJsonObject("animators");
            if(animators!=null)for(var entry:animators.entrySet()){
                JsonObject animator=entry.getValue().getAsJsonObject();
                if (!str(animator, "type", "bone").equals("bone")) continue;
                Map<String,List<Frame>> channels=new HashMap<>();
                for(JsonElement k:array(animator,"keyframes")){
                    var f=k.getAsJsonObject();String channel=str(f,"channel","");
                    if(!Set.of("position","rotation","scale").contains(channel))throw new IOException("Unsupported channel "+channel);
                    String interpolation=str(f,"interpolation","linear");
                    if(!Set.of("linear","catmullrom","step").contains(interpolation))throw new IOException("Unsupported interpolation "+interpolation);
                    if(!str(f,"easing","linear").equals("linear"))throw new IOException("Unsupported easing "+str(f,"easing",""));
                    var points=array(f,"data_points");if(points.isEmpty())continue;
                    channels.computeIfAbsent(channel,x->new ArrayList<>()).add(new Frame(number(f.get("time")),vec(points.get(0)),vec(points.get(points.size()-1)),interpolation));
                }
                channels.values().forEach(list->list.sort(Comparator.comparingDouble(Frame::time)));tracks.put(entry.getKey(),channels);
            }
            model.animations.put(animName,new Animation(animName,Math.max(1,Math.round(number(a.get("length"))*20)),str(a,"loop","once").equals("loop"),tracks));
        }
        return model;
    }
    private void bone(JsonElement node,int parent,float uvX,float uvY)throws IOException{
        if(node.isJsonPrimitive())throw new IOException("Кубы в корне проекта нужно поместить в группу.");
        var out=node.getAsJsonObject();String uuid=out.get("uuid").getAsString();var group=groups.get(uuid);
        if(group==null)group=out;
        if(!enabled(group,"export"))return;
        int index=bones.size();String item="actors/"+id+"/b"+index;Vector3f pivot=vec(group.get("origin"));
        JsonArray elements=new JsonArray();
        bones.add(new Bone(uuid,str(group,"name",uuid),parent,pivot,vec(group.get("rotation")),item));
        for(JsonElement child:array(out,"children"))if(child.isJsonPrimitive()){
            var cube=cubes.get(child.getAsString());if(cube==null)continue;
            Vector3f rotation = vec(cube.get("rotation"));
            JsonObject element=new JsonObject();
            element.add("from",jsonVec(vec(cube.get("from")).sub(pivot).add(8,8,8)));
            element.add("to",jsonVec(vec(cube.get("to")).sub(pivot).add(8,8,8)));
            if (rotation.lengthSquared() > 0) {
                int axes = (rotation.x != 0 ? 1 : 0) + (rotation.y != 0 ? 1 : 0) + (rotation.z != 0 ? 1 : 0);
                float angle = rotation.x != 0 ? rotation.x : rotation.y != 0 ? rotation.y : rotation.z;
                if (axes != 1 || Math.abs(angle) > 45 || angle % 22.5f != 0)
                    throw new IOException("Unsupported cube rotation: " + str(cube, "name", "cube"));
                JsonObject pivotRotation = new JsonObject();
                pivotRotation.addProperty("axis", rotation.x != 0 ? "x" : rotation.y != 0 ? "y" : "z");
                pivotRotation.addProperty("angle", angle);
                pivotRotation.add("origin", jsonVec(vec(cube.get("origin")).sub(pivot).add(8, 8, 8)));
                element.add("rotation", pivotRotation);
            }
            JsonObject faces=new JsonObject();
            var rawFaces=cube.getAsJsonObject("faces");
            if(rawFaces!=null)for(var f:rawFaces.entrySet()){
                var face=f.getValue().getAsJsonObject();JsonElement texture=face.get("texture");if(texture==null||texture.isJsonNull())continue;
                String tex=textures.get(texture.getAsString());if(tex==null)throw new IOException("Unknown texture "+texture);
                var newFace=face.deepCopy();newFace.addProperty("texture","#"+tex);newFace.remove("cullface");
                var uv=face.getAsJsonArray("uv");if(uv!=null){var scaled=new JsonArray();for(int n=0;n<4;n++)scaled.add(number(uv.get(n))*(n%2==0?uvX:uvY));newFace.add("uv",scaled);}
                faces.add(f.getKey(),newFace);
            }
            if (!faces.isEmpty()) { element.add("faces",faces);elements.add(element); }
        }
        if(!elements.isEmpty()){
            JsonObject model=new JsonObject(),tex=new JsonObject();new HashSet<>(textures.values()).forEach(t->tex.addProperty(t,"betteranimation:item/actors/"+id+"/"+t));
            tex.addProperty("particle", "#" + facesTexture(elements));
            model.add("textures",tex);model.add("elements",elements);model.addProperty("ambientocclusion",false);
            write("assets/betteranimation/models/"+item+".json",model);
            JsonObject def=new JsonObject(),inner=new JsonObject();inner.addProperty("type","minecraft:model");inner.addProperty("model","betteranimation:"+item);def.add("model",inner);
            write("assets/betteranimation/items/"+item+".json",def);
        }else bones.set(index,new Bone(uuid,str(group,"name",uuid),parent,pivot,vec(group.get("rotation")),null));
        for(JsonElement child:array(out,"children"))if(child.isJsonObject())bone(child,index,uvX,uvY);
    }
    private static String facesTexture(JsonArray elements) {
        return elements.get(0).getAsJsonObject().getAsJsonObject("faces").entrySet().iterator().next()
            .getValue().getAsJsonObject().get("texture").getAsString().substring(1);
    }
    public static Vector3f sample(List<Frame> frames,double time,Vector3f fallback){
        if(frames==null||frames.isEmpty())return new Vector3f(fallback);
        if(time<frames.getFirst().time())return new Vector3f(frames.getFirst().before());
        int i=0;while(i+1<frames.size()&&frames.get(i+1).time()<=time)i++;
        var a=frames.get(i);if(i+1==frames.size())return new Vector3f(a.after());
        var b=frames.get(i+1);float t=(float)((time-a.time())/(b.time()-a.time()));
        if(a.interpolation().equals("step"))return new Vector3f(a.after());
        if(a.interpolation().equals("catmullrom")||b.interpolation().equals("catmullrom")){
            Vector3f p0=i==0?a.after():frames.get(i-1).after(),p1=a.after(),p2=b.before(),p3=i+2>=frames.size()?b.before():frames.get(i+2).before();
            float t2=t*t,t3=t2*t;
            return new Vector3f(p1).mul(2).add(new Vector3f(p2).sub(p0).mul(t)).add(new Vector3f(p0).mul(2).sub(new Vector3f(p1).mul(5)).add(new Vector3f(p2).mul(4)).sub(p3).mul(t2)).add(new Vector3f(p0).negate().add(new Vector3f(p1).mul(3)).sub(new Vector3f(p2).mul(3)).add(p3).mul(t3)).mul(.5f);
        }
        return new Vector3f(a.after()).lerp(b.before(),t);
    }
    public Matrix4f[] pose(Animation animation,int tick,float yaw){
        Matrix4f[] matrices=new Matrix4f[bones.size()];
        for(int i=0;i<bones.size();i++){
            Bone b=bones.get(i);var tracks=animation.tracks().getOrDefault(b.uuid(),Map.of());
            var delta=sample(tracks.get("position"),tick/20.0,new Vector3f());
            var rotation=sample(tracks.get("rotation"),tick/20.0,new Vector3f()).add(b.rotation());
            var scale=sample(tracks.get("scale"),tick/20.0,new Vector3f(1));
            var pivot=new Vector3f(b.pivot());if(b.parent()>=0)pivot.sub(bones.get(b.parent()).pivot());
            var local=new Matrix4f().translation(pivot.add(delta).div(16)).rotateZYX((float)Math.toRadians(rotation.z),(float)Math.toRadians(rotation.y),(float)Math.toRadians(rotation.x)).scale(scale);
            matrices[i]=b.parent()<0?new Matrix4f().rotateY((float)Math.toRadians(-yaw)).mul(local):new Matrix4f(matrices[b.parent()]).mul(local);
        }
        return matrices;
    }
}
