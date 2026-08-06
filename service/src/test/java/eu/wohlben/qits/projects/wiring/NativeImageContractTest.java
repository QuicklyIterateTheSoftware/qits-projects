package eu.wohlben.qits.projects.wiring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one native-image invariant this package's classes carry, pinned where the JVM suite can still
 * see it — the same rule qits-artifacts' {@code CiPostReceiveNotifier} states and this repo's {@code
 * DnsDomainRegistrar} already follows: a {@code static} {@link HttpClient} is built at
 * image-build time, and native-image refuses the {@code HttpClientFacade} that lands in the heap.
 *
 * <p>Nothing here proves a native image works — that needs the binary. What it prevents is the
 * silent re-introduction: the JVM suite passes with a static client exactly as well as with an
 * instance one, so no other test would notice the field turning static.
 */
public class NativeImageContractTest {

  @Test
  public void noStaticHttpClientInTheWiringPackage() {
    for (Class<?> type : List.of(HttpGitHostRepositories.class)) {
      for (Field field : type.getDeclaredFields()) {
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        assertTrue(
            !isStatic || !HttpClient.class.isAssignableFrom(field.getType()),
            type.getSimpleName()
                + "."
                + field.getName()
                + " is a static HttpClient — it will be constructed into the image heap and the"
                + " native build will fail. Make it an instance field of the bean.");
      }
    }
  }
}
