package eu.wohlben.qits.projects.wiring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one native-image invariant this service's hand-rolled HTTP adapters carry, pinned where the
 * JVM suite can still see it — the same rule qits-artifacts' {@code CiPostReceiveNotifier} states: a
 * {@code static} {@link HttpClient} is built at image-build time, and native-image refuses the
 * {@code HttpClientFacade} that lands in the heap.
 *
 * <p>Nothing here proves a native image works — that needs the binary. What it prevents is the
 * silent re-introduction: the JVM suite passes with a static client exactly as well as with an
 * instance one, so no other test would notice the field turning static.
 *
 * <p>The list is the adapters, not the package: it started as {@code wiring}'s one class and grew
 * the first time an adapter landed in a package of its own.
 */
public class NativeImageContractTest {

  @Test
  public void noStaticHttpClientInThisServicesHttpAdapters() {
    for (Class<?> type :
        List.of(
            HttpGitHostRepositories.class,
            eu.wohlben.qits.projects.workspacehost.HttpReleasedBranchWorkspaces.class)) {
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
