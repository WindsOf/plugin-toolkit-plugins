import ai.koog.prompt.executor.llms.all.OpenAILlmsKt;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public class TestReflection {
    public static void main(String[] args) {
        try {
            Class<?> clazz = Class.forName("ai.koog.prompt.executor.llms.all.OpenAILlmsKt");
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals("simpleOpenAIExecutor")) {
                    System.out.println(method.getName() + ":");
                    for (Parameter param : method.getParameters()) {
                        System.out.println("  " + param.getType().getName() + " " + param.getName());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
