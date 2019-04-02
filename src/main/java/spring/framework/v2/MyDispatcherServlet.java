package spring.framework.v2;

import spring.framework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class MyDispatcherServlet extends HttpServlet {
    // 通过web.xml中配置的配置文件信息，查找application.properties
    private Properties configContext = new Properties();
    // 存储所有扫描到的类
    private List< String > classNames = new ArrayList< String >();
    // IOC容器，保存所有实例化对象
    // 注册式单例模式
    private Map< String, Object > ioc = new HashMap< String, Object >();

    //    private Map< String, Object > mapping = new HashMap<>();
    //保存Contrller中所有Mapping的对应关系
    private Map< String, Method > handlerMapping = new HashMap<>();

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        doPost( req, resp );
    }

    @Override
    protected void doPost( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        try {
            // 委派模式
            doDispatch( req, resp );
        } catch ( InvocationTargetException | IllegalAccessException e ) {
            e.printStackTrace();
            resp.getWriter().write( "500 Excetion Detail:" + Arrays.toString( e.getStackTrace() ) );
        }
    }

    private void doDispatch( HttpServletRequest req, HttpServletResponse resp ) throws IOException, InvocationTargetException, IllegalAccessException {
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace( contextPath, "" ).replaceAll( "/+", "/" );
        if ( !handlerMapping.containsKey( url ) ) {
            resp.getWriter().write( "404 Not Found!!!" );
            return;
        }

        Method method = this.handlerMapping.get( url );
        //获取请求中的实参列表
        Map< String, String[] > params = req.getParameterMap();
        //获取方法的形参列表
        Class< ? >[] parameterTypes = method.getParameterTypes();

        //保存赋值参数的位置
        Object[] paramValues = new Object[ parameterTypes.length ];
        //根据参数位置动态赋值
        for ( int i = 0; i < parameterTypes.length; i++ ) {
            Class< ? > parameterType = parameterTypes[ i ];
            if ( parameterType == HttpServletRequest.class ) {
                paramValues[ i ] = req;
            } else if ( parameterType == HttpServletResponse.class ) {
                paramValues[ i ] = resp;
            } else if ( parameterType == String.class ) {
                final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                final Annotation[] annotations = parameterAnnotations[ i ];
                for ( Annotation annotation : annotations ) {
                    if(annotation instanceof RequestParam) {
                        for ( Map.Entry< String, String[] > param : params.entrySet() ) {
                            String value = Arrays.toString( param.getValue() )
                                    .replaceAll( "\\[|]", "" )
                                    .replaceAll( "\\s", "," );
                            paramValues[ i ] = value;
                        }
                    }
                }
            }
        }
        //投机取巧的方式
        String beanName = toLowerFirstCase( method.getDeclaringClass().getSimpleName() );
        method.invoke( ioc.get( beanName ), paramValues );
    }

    @Override
    public void init( ServletConfig config ) {

        //1.加载配置文件
        doLoadConfig( config.getInitParameter( "contextConfigLocation" ) );
        //2.扫描相关的类
        doScaner( configContext.getProperty( "scanPackage" ) );
        //3.初始化所有相关类的实例，并放入到IOC容器中
        doInstance();
        //4.完成依赖注入
        doAutowired();
        //5.初始化HandlerMapping
        initHanlderMapping();

        System.out.println( "DengJL Spring Framework initial has finished" );
    }

    private void initHanlderMapping() {
        if ( ioc.isEmpty() ) {
            return;
        }
        for ( Map.Entry< String, Object > entry : ioc.entrySet() ) {
            Class< ? > clazz = entry.getValue().getClass();
            if ( !clazz.isAnnotationPresent( Controller.class ) ) {
                continue;
            }

            String baseUrl = "";
            //获取Controller的url配置
            if ( clazz.isAnnotationPresent( RequestMapping.class ) ) {
                RequestMapping requestMapping = clazz.getAnnotation( RequestMapping.class );
                baseUrl = requestMapping.value();
            }

            //获取Method的url配置
            Method[] methods = clazz.getMethods();
            for ( Method method : methods ) {

                //没有加RequestMapping注解的直接忽略
                if ( !method.isAnnotationPresent( RequestMapping.class ) ) {
                    continue;
                }

                //映射URL
                RequestMapping requestMapping = method.getAnnotation( RequestMapping.class );
                //  /demo/query
                //  (//demo//query)
                String url = ( "/" + baseUrl + "/" + requestMapping.value() ).replaceAll( "/+", "/" );
                handlerMapping.put( url, method );
                System.out.println( "Mapped " + url + "," + method );
            }
        }
    }

    /**
     * 4.完成依赖注入
     */
    private void doAutowired() {
        // 为什么要先循环一遍mapping，再单独循环一遍
        // 因为可能在设置Autowried的时候，可能对应的对象还没有被初始化，导致报错。
        for ( Object object : ioc.values() ) {
            final Class< ? > clazz = object.getClass();
            // 得到该类中所有成员
            final Field[] fields = clazz.getDeclaredFields();
            for ( Field field : fields ) {
                // 处理所有带有Autowired的成员变量，完成自动注入
                if ( field.isAnnotationPresent( Autowired.class ) ) {
                    Autowired autowired = field.getAnnotation( Autowired.class );
                    // 从注解中取出用户自定义的成员变量的别名
                    String beanName = autowired.value().trim();
                    if ( "".equals( beanName ) ) {
                        // 如果没有取到用户自定义的变量名，则设置为变量的类型
                        beanName = field.getType().getName();
                    }
                    // 处理私有成员变量
                    field.setAccessible( true );
                    try {
                        // 在mapping中找到对应实例，注入到该成员变量中
                        field.set( object, ioc.get( beanName ) );
                    } catch ( IllegalAccessException e ) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 3.初始化所有相关类的实例，并放入到IOC容器中
     */
    private void doInstance() {
        for ( String className : classNames ) {
            if ( !className.contains( "." ) ) {
                continue;
            }
            try {
                Class< ? > clazz = Class.forName( className );
                // 处理所有Controller类
                if ( clazz.isAnnotationPresent( Controller.class ) ) {
                    // 把满足类型为Controller的类进行实例化，并放入到mapping中
                    ioc.put( toLowerFirstCase( clazz.getSimpleName() ), clazz.newInstance() );
                } else if ( clazz.isAnnotationPresent( Service.class ) ) {
                    //1、默认的类名首字母小写
                    String beanName = toLowerFirstCase( clazz.getSimpleName() );
                    //2、自定义命名
                    Service service = clazz.getAnnotation( Service.class );
                    if ( !"".equals( service.value() ) ) {
                        beanName = service.value();
                    }
                    final Object instance = clazz.newInstance();
                    ioc.put( beanName, instance );
                    //3、根据类型注入实现类，投机取巧的方式
                    for ( Class< ? > i : clazz.getInterfaces() ) {
                        if ( ioc.containsKey( i.getName() ) ) {
                            throw new RuntimeException( "The beanName is exists!!" );
                        }
                        ioc.put( i.getName(), instance );
                    }
                }
            } catch ( Exception e ) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 2.载入application.properties
     *
     * @param propertiesPath
     */
    private void doLoadConfig( String propertiesPath ) {
        try ( InputStream is = this.getClass().getClassLoader().getResourceAsStream( propertiesPath ) ) {
            configContext.load( is );
        } catch ( IOException e ) {
            e.printStackTrace();
        }
    }

    private String toLowerFirstCase( String simpleName ) {
        char firstChar = simpleName.charAt( 0 );
        if ( Character.isLowerCase( firstChar ) ) {
            return simpleName;
        } else {
            return Character.toLowerCase( firstChar ) + simpleName.substring( 1 );
        }
    }

    /**
     * 1.扫描配置文件中指定的包路径下的类文件
     *
     * @param scanPackage
     */
    private void doScaner( final String scanPackage ) {
        final URL resource = this.getClass().getClassLoader().getResource( "/" + scanPackage.replaceAll( "\\.", "/" ) );
        try {
            final Path packageDir = Paths.get( resource.toURI() );
            Files.newDirectoryStream( packageDir ).forEach( path -> {
                if ( path.toFile().isDirectory() ) {
                    doScaner( scanPackage + "." + path.getFileName() );
                } else {
                    // 存入的类名类似：spring.demo.mvc.DemoAction
                    String clazzName = ( scanPackage + "." + path.getFileName() ).replace( ".class", "" );
                    classNames.add( clazzName );
                }
            } );
        } catch ( URISyntaxException | IOException e ) {
            e.printStackTrace();
        }
    }
}
