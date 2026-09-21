import org.springframework.security.crypto.bcrypt.BCrypt;

public class Gen {
    public static void main(String[] args) {
        System.out.println(BCrypt.hashpw(args[0], BCrypt.gensalt(10)));
    }
}
