dubbo-gzip跟老的dubbo传输协议不兼容，其他跟dubbo一样
http://dubbo.io/Home-zh.htm
================================================================
Quick Start
================================================================
Export remote service:
	
    <bean id="barService" class="com.foo.BarServiceImpl" />
	
    <dubbo:service interface="com.foo.BarService" ref="barService" gzip="true" gziplen="1024"/>

Refer remote service:

    <dubbo:reference id="barService" interface="com.foo.BarService" gzip="true" gziplen="1024"/>
	
    <bean id="barAction" class="com.foo.BarAction">
        <property name="barService" ref="barService" />
    </bean>

更多详细请参考源码中的demo
